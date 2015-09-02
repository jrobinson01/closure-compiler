/*
 * Copyright 2015 The Closure Compiler Authors.
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

package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfo.Visibility;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inline aliases created by exports of modules before type checking.
 *
 * The old type inference doesn't deal as well with aliased types as with unaliased ones,
 * such as in extends clauses (@extends {alias}) and templated types (alias<T>).
 * This pass inlines these aliases to make type checking's job easier.
 *
 * @author blickly@gmail.com (Ben Lickly)
 */
final class InlineAliases extends AbstractPostOrderCallback implements CompilerPass {

  private final AbstractCompiler compiler;
  private final Map<String, String> aliases = new LinkedHashMap<>();
  private GlobalNamespace namespace;

  InlineAliases(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    namespace = new GlobalNamespace(compiler, root);
    NodeTraversal.traverseEs6(compiler, root, this);
  }

  /**
    * Maybe record that given lvalue is an alias of the qualified name on its rhs.
    * Note that since we are doing a post-order traversal, any previous aliases contained in
    * the rhs will have already been substituted by the time we record the new alias.
    */
  private void visitAliasDefinition(Node lhs, JSDocInfo info) {
    if (info != null && info.hasConstAnnotation() && lhs.isQualifiedName()) {
      Node rhs = NodeUtil.getRValueOfLValue(lhs);
      if (rhs != null && rhs.isQualifiedName()) {
        GlobalNamespace.Name lhsName = namespace.getOwnSlot(lhs.getQualifiedName());
        GlobalNamespace.Name rhsName = namespace.getOwnSlot(rhs.getQualifiedName());
        if (lhsName != null && lhsName.isInlinableGlobalAlias()
            && rhsName != null && rhsName.isInlinableGlobalAlias()
            && !isPrivate(rhsName.getDeclaration().getNode())) {
          aliases.put(lhs.getQualifiedName(), rhs.getQualifiedName());
        }
      }
    }
  }

  private boolean isPrivate(Node nameNode) {
    if (nameNode.isQualifiedName()
        && compiler.getCodingConvention().isPrivate(nameNode.getQualifiedName())) {
      return true;
    }
    JSDocInfo info = NodeUtil.getBestJSDocInfo(nameNode);
    return info != null && info.getVisibility().equals(Visibility.PRIVATE);
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getType()) {
      case Token.VAR:
        if (n.getChildCount() == 1 && t.inGlobalScope()) {
          visitAliasDefinition(n.getFirstChild(), n.getFirstChild().getJSDocInfo());
        }
        break;
      case Token.ASSIGN:
        if (parent != null && parent.isExprResult() && t.inGlobalScope()) {
          visitAliasDefinition(n.getFirstChild(), n.getJSDocInfo());
        }
        break;
      case Token.NAME:
      case Token.GETPROP:
        if (n.isQualifiedName() && aliases.containsKey(n.getQualifiedName())) {
          String leftmostName = NodeUtil.getRootOfQualifiedName(n).getString();
          Var v = t.getScope().getVar(leftmostName);
          if (v != null && v.isLocal()) {
            // Shadow of alias. Don't rewrite
            return;
          }
          Preconditions.checkState(!NodeUtil.isVarOrSimpleAssignLhs(n, parent));
          parent.replaceChild(n, NodeUtil.newQName(compiler,
                aliases.get(n.getQualifiedName())).copyInformationFromForTree(n));
          compiler.reportCodeChange();
        }
        break;
    }
    maybeRewriteJsdoc(n.getJSDocInfo());
  }

  private void maybeRewriteJsdoc(JSDocInfo info) {
    if (info == null) {
      return;
    }
    for (Node typeNode : info.getTypeNodes()) {
      NodeUtil.visitPreOrder(typeNode, fixJsdocTypeNodes, Predicates.<Node>alwaysTrue());
    }
  }

  private final NodeUtil.Visitor fixJsdocTypeNodes = new NodeUtil.Visitor() {
    public void visit(Node aliasReference) {
      if (!aliasReference.isString()) {
        return;
      }
      String fullTypeName = aliasReference.getString();
      int dotIndex = 0;
      do {
        dotIndex = fullTypeName.indexOf('.', dotIndex + 1);
        String aliasName = dotIndex == -1 ? fullTypeName : fullTypeName.substring(0, dotIndex);
        if (aliases.containsKey(aliasName)) {
          String replacement = aliases.get(aliasName) + fullTypeName.substring(aliasName.length());
          aliasReference.setString(replacement);
          return;
        }
      } while (dotIndex != -1);
    }
  };
}