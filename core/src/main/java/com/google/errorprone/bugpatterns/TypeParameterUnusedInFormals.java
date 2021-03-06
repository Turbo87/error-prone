/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.bugpatterns;

import static com.google.errorprone.BugPattern.Category.JDK;
import static com.google.errorprone.BugPattern.MaturityLevel.MATURE;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;

import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCTypeCast;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.TreeScanner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@BugPattern(name = "TypeParameterUnusedInFormals",
    summary = "Declaring a type parameter that is only used in the return type is a misuse of"
    + " generics: operations on the type parameter are unchecked, it hides unsafe casts at"
    + " invocations of the method, and it interacts badly with method overload resolution",
    category = JDK, severity = WARNING, maturity = MATURE)
public class TypeParameterUnusedInFormals extends BugChecker implements MethodTreeMatcher {

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    MethodSymbol methodSymbol = ASTHelpers.getSymbol(tree);
    if (methodSymbol == null) {
      return Description.NO_MATCH;
    }

    // Only match methods where the return type is just a type parameter.
    // e.g. the following is OK: <T> List<T> newArrayList();
    TypeVar retType;
    switch (methodSymbol.getReturnType().getKind()) {
      case TYPEVAR:
        retType = (TypeVar) methodSymbol.getReturnType();
        break;
      default:
        return Description.NO_MATCH;
    }

    // Ignore f-bounds.
    // e.g.: <T extends Enum<T>> T unsafeEnumDeserializer();
    if (retType.bound != null && TypeParameterFinder.visit(retType.bound).contains(retType)) {
      return Description.NO_MATCH;
    }

    // Ignore cases where the type parameter is used in the declaration of a formal parameter.
    // e.g.: <T> T noop(T t);
    for (VarSymbol formalParam : methodSymbol.getParameters()) {
      if (TypeParameterFinder.visit(formalParam.type).contains(retType)) {
        return Description.NO_MATCH;
      }
    }

    return attemptFix(retType, tree, state);
  }

  private Description attemptFix(final TypeVar retType, MethodTree tree, VisitorState state) {
    CharSequence source = state.getSourceForNode((JCTree) tree);
    if (source == null) {
      // No fix if we don't have end positions.
      return describeMatch(tree);
    }

    int paramIndex = -1;
    for (int idx = 0; idx < tree.getTypeParameters().size(); ++idx) {
      if (((JCTypeParameter) tree.getTypeParameters().get(idx)).type.equals(retType)) {
        paramIndex = idx;
        break;
      }
    }

    if (paramIndex == -1) {
      return Description.NO_MATCH;
    }

    SuggestedFix.Builder fix = SuggestedFix.builder();
    fix = removeTypeParam(paramIndex, tree.getTypeParameters(), state, fix);

    // Replace usages of the type parameter with its upper-bound, both inside the method body
    // and in the return type.
    // e.g. <A, B> B f(A a) { return (B) a; } -> <A> Object f(A a) { return (Object) a; }
    String qualifiedName = retType.bound != null ? retType.bound.toString() : "Object";
    // Always use simple names.
    // TODO(cushon) - this isn't always correct, but it's better than defaulting to
    // fully-qualified names. There should be a better way to do this.
    String newType = Iterables.getLast(Splitter.on('.').split(qualifiedName));

    rewriteTypeUsages(retType, tree.getBody(), newType, fix, state);
    rewriteTypeUsages(retType, tree.getReturnType(), newType, fix, state);

    return describeMatch(tree, fix.build());
  }

  // Remove the type parameter declaration.
  // e.g. <A, B> B f(A a); -> <A> B f(A a);
  private SuggestedFix.Builder removeTypeParam(
      int paramIndex,
      List<? extends TypeParameterTree> tyParams,
      VisitorState state,
      SuggestedFix.Builder fix) {

    JCTree typeParam = (JCTree) tyParams.get(paramIndex);
    boolean isFirst = paramIndex == 0;
    boolean isLast = paramIndex == (tyParams.size() - 1);

    int startDeletion = isFirst
        ? typeParam.getStartPosition()
        : state.getEndPosition((JCTree) tyParams.get(paramIndex - 1));

    int endDeletion = isLast
        ? state.getEndPosition(typeParam)
        : ((JCTree) tyParams.get(paramIndex + 1)).getStartPosition();

    // if it's an interior deletion, leave a comma to separate the neighbouring params
    String replacement = (isFirst || isLast) ? "" : ", ";

    if (isFirst && isLast) {
      // Remove leading "<" and trailing "> "
      startDeletion -= 1;
      endDeletion += 2;
    }

    fix = fix.replace(startDeletion, endDeletion, replacement);
    return fix;
  }

  private static void rewriteTypeUsages(
      final TypeVar retType,
      Tree tree,
      final String newType,
      final SuggestedFix.Builder fix,
      final VisitorState state) {
    if (tree == null) {
      // e.g. abstract methods without bodies.
      return;
    }
    ((JCTree) tree)
        .accept(
            new TreeScanner() {
              @Override
              public void visitIdent(JCIdent node) {
                if (retType.tsym.equals(node.sym)) {
                  fix.replace(node, newType);
                }
              }

              @Override
              public void visitTypeCast(JCTypeCast node) {
                if (ASTHelpers.isSameType(retType.tsym.type, node.type.tsym.type, state)
                    && newType.equals("Object")) {
                  fix.replace(node, state.getSourceForNode(node.getExpression()).toString());
                } else {
                  super.visitTypeCast(node);
                }
              }
            });
  }

  /**
   * A visitor that records the set of {@link com.sun.tools.javac.code.Type.TypeVar}s referenced by
   * the current type.
   */
  private static class TypeParameterFinder extends Types.DefaultTypeVisitor<Void, Void> {

    static Set<Type.TypeVar> visit(Type type) {
      TypeParameterFinder visitor = new TypeParameterFinder();
      type.accept(visitor, null);
      return visitor.seen;
    }

    private Set<Type.TypeVar> seen = new HashSet<Type.TypeVar>();

    @Override
    public Void visitClassType(Type.ClassType type, Void unused) {
      if (type instanceof Type.IntersectionClassType) {
        // TypeVisitor doesn't support intersection types natively, so we have
        // to handle visiting them manually.
        visitIntersectionClassType((Type.IntersectionClassType) type);
      } else {
        for (Type t : type.getTypeArguments()) {
          t.accept(this, null);
        }
      }
      return null;
    }

    public void visitIntersectionClassType(Type.IntersectionClassType type) {
      for (Type component : type.getComponents()) {
        component.accept(this, null);
      }
    }

    @Override
    public Void visitWildcardType(Type.WildcardType type, Void unused) {
      if (type.getSuperBound() != null) {
        type.getSuperBound().accept(this, null);
      }
      if (type.getExtendsBound() != null) {
        type.getExtendsBound().accept(this, null);
      }
      return null;
    }

    @Override
    public Void visitArrayType(Type.ArrayType type, Void unused) {
      type.elemtype.accept(this, null);
      return null;
    }

    @Override
    public Void visitTypeVar(Type.TypeVar type, Void unused) {
      // only visit f-bounds once:
      if (!seen.add(type)) {
        return null;
      }
      if (type.bound != null) {
        type.bound.accept(this, null);
      }
      return null;
    }

    @Override
    public Void visitType(Type type, Void unused) {
      return null;
    }
  }
}
