package com.forenzix.interpreter;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Parser {
    
    private final List<Token> cache;
    private final Tokeniser tokeniser;
    private int lineNumber = 1, lineNumberCache = 1;
    private NodeScope root = null;

    public Parser(Tokeniser tokeniser) {
        this.tokeniser = tokeniser;
        this.cache = new LinkedList<>();
    }
    
    public Parser(String input) {
        // The input expression should always end in an End of File token (\0 character)
        this(new Tokeniser(input + Token.EOF));
    }

    public NodeScope parse() {
        tokeniser.reset();
        lineNumber = 1;
        root = parseProgram();

        if (peek() != Token.EOT) {
            throw error("Unexpected token at End of Expression: " + peek().value);
        }
        return root;
    }
    
    // MARK: Parse Scope
    // Scope -> '{' [Program] '}'
    private NodeScope parseScope() {
        if (tryConsume(Token.OpenCurly)) {
            final NodeScope scope = parseProgram();
            if (scope == null || scope.statements.isEmpty()) return null;

            tryConsume(Token.CloseCurly, "Expected '}'");
            return scope;
        }
        return null;
    }

    // Program -> [StmtTerm]* ([Statement] ([StmtTerm]+ [Statement])* [StmtTerm]*)?
    private NodeScope parseProgram() {
        final NodeScope scope = new NodeScope(new LinkedList<>());

        // [StmtTerm]*
        while (tryConsume(TokenType.StatementTerminator) != null);
        
        // [Statement]?
        final NodeStmt first = parseStatement();
        if (first == null) return scope;
        scope.statements.add(first);

        // ([StmtTerm]+ [Statement])* [StmtTerm]*
        while (peek() != Token.EOT) {

            // [StmtTerm]+ | [StmtTerm]*
            if (!peek().isAny(TokenType.StatementTerminator)) return scope;
            while (tryConsume(TokenType.StatementTerminator) != null);
            
            // [Statement]
            final NodeStmt statement = parseStatement();
            if (statement == null) return scope;
            scope.statements.add(statement);
        }

        return scope;
    }
    
    // MARK: Parse Statement
    
    // Statement -> [Decl] | [If] | [While] | [ForEach] | [ForLoop] | [Scope] | 
    //   [Try] | [Func] | [Break] | [Continue] | [Return] |
    //   [Assign] | [Expr] | [Comment]
    private NodeStmt parseStatement() {
        NodeStmt statement;

        // Declaration
        if ((statement = parseDecl()) != null);

        // If statement
        else if ((statement = parseIf()) != null);
        
        // While loop
        else if ((statement = parseWhile()) != null);
        
        // For loop
        else if ((statement = parseFor()) != null);

        // Standalone scope
        else if ((statement = parseScopeStmt()) != null);
        
        // Jump statements
        else if ((statement = parseJumpStmt()) != null);
        
        // Function
        else if ((statement = parseFunction()) != null);

        // Try Catch
        else if ((statement = parseTryCatch()) != null);
        
        // Disambiguate between assignments and expressions
        else {

            final NodeTerm start = parseTerm();
            final boolean assignType = start instanceof NodeTerm.ArrayAccess || 
                start instanceof NodeTerm.PropAccess || 
                start instanceof NodeTerm.Variable;

            // Assignment
            if (assignType && peek().isAny(TokenType.AssignOperator)) {
                final Token op = tryConsume(TokenType.AssignOperator);
                final NodeExpr expr = tryParse(() -> parseExpression(), "Expected expression");
                return arthmeticAssignNode(op, start, expr);
            }

            // Bare Expression
            else {
                final NodeExpr expr = parseExpression(new NodeExpr.Term(start), 0);
                statement = expr == null ? null : new NodeStmt.Expr(expr);
            }
        }

        return statement;
    }
    
    // Decl -> 'let' [Variable] '=' [Expr]
    private NodeStmt.Declare parseDecl() {
        if (tryConsume(Token.Let)) {
            final NodeVar var = tryParse(() -> parseVariable(), "Expected qualifier: " + peek().value);
            final NodeExpr expr;
            if (!tryConsume(Token.EqualSign)) {
                // Default value of declared variables is null
                expr = new NodeExpr.Term(new NodeTerm.Literal<Void>(null));
            }
            else {
                expr = tryParse(() -> parseExpression(), "Expected expression.");
            }
   
            return new NodeStmt.Declare(var, expr);
        }
        return null;
    }

    // If -> 'if' [Expr] [Scope] ('else if' [Expr] [Scope])* ('else' [Scope])?
    private NodeStmt.If parseIf() {
        if (tryConsume(Token.If)) {
            final NodeExpr expr = tryParse(() -> parseExpression(), "Expected condition.");
            final NodeScope scope = tryParse(() -> parseScope(), "Unparsable Scope.");
            
            final NodeScope scopeElse;
            if (!tryConsume(Token.Else)) {
                scopeElse = peek() == Token.If ? 
                new NodeScope(List.of(parseIf())) : 
                tryParse(() -> parseScope(), "Expected else block.");
            }
            else {
                scopeElse = null;
            }
            return new NodeStmt.If(expr, scope, scopeElse);
        }
        return null;
    }

    // While -> 'while' [Expr] [Scope]
    private NodeStmt.While parseWhile() {
        if (tryConsume(Token.While)) {
            return new NodeStmt.While(
                tryParse(() -> parseExpression(), "Expected condition."), 
                tryParse(() -> parseScope(), "Unparsable Scope.")
            );
        }

        return null;
    }

    
    // ForEach -> 'for' '(' [Qualifier] 'in' [Term] ')' [Scope]
    // ForLoop -> 'for' '(' ([Assign] | [Decl])? ';' [Expr]? ';' ([Assign] | [Expr])? ')' [Scope]
    private NodeStmt parseFor() {
        if (tryConsume(Token.For)) {
            tryConsume(Token.OpenParen, "Expected '('");

            // Check if it is a valid for each
            boolean foreach = false;
            if (peek().isAny(TokenType.Qualifier)) {
                int ahead = 1;
                while (peek(ahead) == Token.Newline || peek(ahead).isAny(TokenType.Comment)) ahead += 1;
                foreach = peek() == Token.In;
            }
            
            // For Each
            if (foreach) {
                final NodeVar iterator, list;
                iterator = parseVariable();
                tryConsume(Token.In);
                list = tryParse(() -> parseVariable(), "Expected variable.");
                tryConsume(Token.CloseParen, "Expected ')'");

                final NodeScope scope = tryParse(() -> parseScope(), "Unparsable Scope.");
                return new NodeStmt.ForEach(iterator, list, scope);
            }

            // Normal For
            else {
                final NodeStmt.Declare init;
                final NodeExpr condition;
                final NodeStmt.Assign increment;

                init = peek() != Token.SemiColon ? tryParse(() -> parseDecl(), "Expected declaration.") : null;
                tryConsume(Token.SemiColon, "Expected ';'");
                condition = peek() != Token.SemiColon ? tryParse(() -> parseExpression(), "Expected boolean expression") : null;
                tryConsume(Token.SemiColon, "Expected ';'");
                increment = peek() != Token.CloseParen ? tryParse(() -> parseAssignment(), "Expected increment expression") : null;

                tryConsume(Token.CloseParen, "Expected ')'");
                final NodeScope scope = tryParse(() -> parseScope(), "Unparsable Scope.");
                return new NodeStmt.For(init, condition, increment, scope);
            }
        }

        return null;
    }

    private NodeStmt.Scope parseScopeStmt() {
        final NodeScope scope = parseScope();
        return scope == null ? null : new NodeStmt.Scope(scope);
    }
    
    // Break -> 'break'
    // Continue -> 'continue'
    // Return -> 'return' [Expr]?
    private NodeStmt parseJumpStmt() {
        if (tryConsume(Token.Break)) return new NodeStmt.Break();
        else if (tryConsume(Token.Continue)) return new NodeStmt.Continue();
        else if (tryConsume(Token.Return)) return new NodeStmt.Return(parseExpression());

        return null;
    }

    // Func -> 'define' [Variable] '(' ([Param] (',' [Param])*)? ')' [Scope]
    private NodeStmt.Function parseFunction() {
        if (tryConsume(Token.Define)) {
            final NodeVar function = tryParse(() -> parseVariable(), "Expected function name");
            tryConsume(Token.OpenParen, "Expected '('");
            final List<NodeParam> params = new LinkedList<>();
            if (peek() != Token.CloseParen) {
                do {
                    params.add(tryParse(() -> parseParam(), "Unexpected Token: " + peek().value));
                } while (tryConsume(Token.Comma));
            }
            tryConsume(Token.CloseParen, "Expected ')'");
            final NodeScope body = tryParse(() -> parseScope(), "Expected function body");
            return new NodeStmt.Function(function, params, body);
        }
        return null;
    }

    // Param -> [Variable] ('=' [Expr])
    private NodeParam parseParam() {
        final NodeVar param = parseVariable();
        if (param != null) {
            if (tryConsume(Token.EqualSign)) {
                return new NodeParam(param, tryParse(() -> parseExpression(), "Expected default paramter value"));
            }
            return new NodeParam(param, null);
        }
        return null;
    }
    
    // Try -> 'try' [Scope] ('catch' [Qualifier] [Scope])? ('finally' [Scope])?
    private NodeStmt.TryCatch parseTryCatch() {
        if (tryConsume(Token.Try)) {
            final NodeScope tryScope, catchScope, finallyScope;
            final NodeVar err;

            tryScope = tryParse(() -> parseScope(), "Expected try block.");
            if (tryConsume(Token.Catch)) {
                tryConsume(Token.OpenParen, "Expected '('");
                err = tryParse(() -> parseVariable(), "Expected error variable");
                tryConsume(Token.CloseParen, "Expected ')'");
                catchScope = tryParse(() -> parseScope(), "Expected catch block.");
            }
            else {
                catchScope = null; err = null;
            }

            finallyScope = tryConsume(Token.Finally) ? tryParse(() -> parseScope(), "Expected finally block.") : null;
            return new NodeStmt.TryCatch(tryScope, catchScope, err, finallyScope);
        }
        return null;
    }

    // MARK: Parse Expression
    private NodeExpr parseExpression() {
        cacheLineNumber();
        return lineNumerise(parseExpression(parseTerm(), 0));
    }
    
    private NodeExpr parseExpression(NodeExpr left, int prec) {
        cacheLineNumber();
        NodeExpr expr = left;
        while (peek().isAny(TokenType.BinaryArithmetic) && peek().precedence >= prec) {
            final Token op = consume();
            NodeExpr right = parseTerm();

            while (peek().isAny(TokenType.BinaryArithmetic) && (
                (peek().precedence > op.precedence) || 
                (peek().precedence >= op.precedence && peek().rightassoc))) {
                right = parseExpression(right, op.precedence + (peek().precedence > op.precedence ? 1 : 0));
            }
            expr = arthmeticNode(op, expr, right);
        }

        return lineNumerise(expr);
    }

    private NodeExpr arthmeticNode(Token op, NodeExpr lhs, NodeExpr rhs) {
        if (op == Token.Plus)                   return new NodeExpr.Binary(BinaryOp.Add, lhs, rhs);
        else if (op == Token.Hyphen)            return new NodeExpr.Binary(BinaryOp.Subtract, lhs, rhs);
        else if (op == Token.Asterisk)          return new NodeExpr.Binary(BinaryOp.Multiply, lhs, rhs);
        else if (op == Token.ForwardSlash)      return new NodeExpr.Binary(BinaryOp.Divide, lhs, rhs);
        else if (op == Token.Percent)           return new NodeExpr.Binary(BinaryOp.Modulo, lhs, rhs);
        else if (op == Token.Caret)             return new NodeExpr.Binary(BinaryOp.Exponent, lhs, rhs);
        else if (op == Token.Greater)           return new NodeExpr.Binary(BinaryOp.Greater, lhs, rhs);
        else if (op == Token.GreaterEqual)      return new NodeExpr.Binary(BinaryOp.GreaterEqual, lhs, rhs);
        else if (op == Token.Less)              return new NodeExpr.Binary(BinaryOp.Less, lhs, rhs);
        else if (op == Token.LessEqual)         return new NodeExpr.Binary(BinaryOp.LessEqual, lhs, rhs);
        else if (op == Token.Equals)            return new NodeExpr.Binary(BinaryOp.Equal, lhs, rhs);
        else if (op == Token.NotEquals)         return new NodeExpr.Binary(BinaryOp.NotEqual, lhs, rhs);
        else if (op == Token.And)               return new NodeExpr.Binary(BinaryOp.And, lhs, rhs);
        else if (op == Token.Or)                return new NodeExpr.Binary(BinaryOp.Or, lhs, rhs);
        else {
            throw error("Unsupported arithmetic operation: " + peek().value);
        }
    }

    private NodeStmt.Assign arthmeticAssignNode(Token op, NodeTerm lhs, NodeExpr rhs) {
        if (op == Token.PlusEqual)         return new NodeStmt.Assign(AssignOp.AddEqual, lhs, rhs);
        else if (op == Token.SubtractEqual)     return new NodeStmt.Assign(AssignOp.SubEqual, lhs, rhs);
        else if (op == Token.MultiplyEqual)     return new NodeStmt.Assign(AssignOp.MultiplyEqual, lhs, rhs);
        else if (op == Token.ModEqual)          return new NodeStmt.Assign(AssignOp.ModEqual, lhs, rhs);
        else if (op == Token.DivideEqual)       return new NodeStmt.Assign(AssignOp.DivideEqual, lhs, rhs);
        else if (op == Token.AndEqual)          return new NodeStmt.Assign(AssignOp.AndEqual, lhs, rhs);
        else if (op == Token.OrEqual)           return new NodeStmt.Assign(AssignOp.OrEqual, lhs, rhs);
        else {
            throw error("Unsupported arithmetic operation: " + peek().value);
        }
    }

    private NodeExpr arthmeticNode(Token op, NodeExpr val) {
        if (op == Token.Hyphen) return new NodeExpr.Unary(UnaryOp.Negate, val);
        else if (op == Token.Tilde) return new NodeExpr.Unary(UnaryOp.Invert, val);
        else if (op == Token.Not) return new NodeExpr.Unary(UnaryOp.Not, val);
        else if (op == Token.Exclaim) return new NodeExpr.Unary(UnaryOp.Not, val);
        else {
            throw error("Unsupported unary arithmetic operation: " + peek().value);
        }
    }

    // MARK: Parse Term
    private NodeTerm parseTerm() {
        cacheLineNumber();
        if (tryConsume(Token.OpenParen)) {
            final NodeExpr exp = tryParse(() -> parseExpression(), "Unparsable/Invalid expression");
            tryConsume(Token.CloseParen, "Expected ')'");
            return exp;
        } 
        else if (tryConsume(Token.OpenSquare)) {
            final List<NodeExpr> expressions = new LinkedList<>();
            while (true) {
                final NodeExpr expr = tryParse(() -> parseExpression(), "Expected Expression");
                expressions.add(expr);

                if (tryConsume(Token.Comma)) {
                    continue;
                }
                else if (tryConsume(Token.CloseSquare)) {
                    break;
                }    
            }
            return new NodeExpr.Term(new NodeTerm.ArrayLiteral(expressions));
        }
        else if (peek().isAny(TokenType.UnaryArithmetic)) {
            final Token op = consume();
            final NodeExpr expr = tryParse(() -> parseTerm(), "Unsupported unary operation: " + op);
            return lineNumerise(arthmeticNode(op, expr));
        }
        else if (peek().isAny(TokenType.Qualifier)) {
            final NodeTerm atom;
            final NodeVar var = parseVariable();
            if (tryConsume(Token.OpenSquare)) {
                final NodeExpr expr = tryParse(() -> parseExpression(), "Expected expression");
                tryConsume(Token.CloseSquare, "Expected closing bracket ']'");
                atom = new NodeTerm.ArrayAccess(var, expr);
            }
            else if (peek() != Token.Period) {
                atom = new NodeTerm.Variable(var);
            }
            else {
                List<String> memberBuilder = new LinkedList<>();
                while (tryConsume(Token.Period)) {
                    final NodeVar member = tryParse(() -> parseVariable(), "Expected member name");
                    memberBuilder.add(member.name);
                }
                atom = new NodeTerm.PropAccess(var, new NodeVar(String.join(".", memberBuilder)));
            }
            return lineNumerise(new NodeExpr.Term(atom));
        } 
        else {
            final NodeTerm atom = parseLiteral();
            return atom == null ? null : lineNumerise(new NodeExpr.Term(atom));
        }
    }

    // MARK: Parse Variable
    private NodeVar parseVariable() {
        if (!peek().isAny(TokenType.Qualifier)) {
            return null;   
        }

        return new NodeVar(consume().value);
    }

    // MARK: Parse Literal
    private NodeTerm.Literal<?> parseLiteral() {

        if (tryConsume(Token.Empty)) {
            return new NodeTerm.Literal<Void>(null);
        }

        if (peek().isAny(TokenType.BooleanLiteral)) 
            return new NodeTerm.Literal<Boolean>(consume().equals(Token.True));
        else if (peek().isAny(TokenType.StringLiteral))
            return new NodeTerm.Literal<String>(consume().value);
        else if (peek().isAny(TokenType.NumberLiteral)) {
            final String repr = consume().value;
            try {
                return new NodeTerm.Literal<Integer>(Integer.parseInt(repr));
            }
            catch (NumberFormatException e) {
                return new NodeTerm.Literal<Double>(Double.parseDouble(repr));
            }
        }
        else {
            return null;
        }
    }

    // MARK: Token Handling
    private Token peek() {
        return peek(0);
    }
    
    private Token consume() {
        final Token consumable = cache.size() > 0 ? cache.remove(0) : tokeniser.nextToken();
        if (!consumable.isAny(TokenType.StringLiteral)) {
            lineNumber += consumable.value.chars().filter(i -> i == '\n').count(); // Possible improvement
        }
        return consumable;
    }

    private Token peek(int offset) {
        while (cache.size() <= offset) {
            cache.add(tokeniser.nextToken());
        }
        return cache.get(offset);
    }

    private boolean tryConsume(Token token) {
        return tryConsume(token, true);
    }

    private boolean tryConsume(Token token, boolean skipBlank) {
        final boolean success;
        if (success = peek() == token) {
            consume();
            if (skipBlank) skipBlank();
        }
        return success;
    }

    private void skipBlank() {
        while (peek() == Token.Newline || peek().isAny(TokenType.Comment)) consume();
    }

    private Token tryConsume(TokenType... types) {
        return tryConsume(true, types);
    }

    private Token tryConsume(boolean skipBlank, TokenType... types) {
        if (peek().isAny(types) && peek() != Token.EOT) {
            final Token token = consume();
            if (skipBlank) skipBlank();
            return token;
        }
        return null;
    }

    private void tryConsume(Token token, String message) {
        if (!tryConsume(token)) {
            throw error(message);
        }
    }

    // MARK: Helpers
    public NodeScope getRoot() {
        return root;
    }

    private RuntimeException error(String message) {
        return new RuntimeException(message + " (line: " + lineNumber + ")");
    }
    
    private void cacheLineNumber() {
        lineNumberCache = lineNumber;
    }

    private NodeExpr lineNumerise(NodeExpr expr) {
        if (expr != null && expr.lineNumber == 0) {
            expr.lineNumber = lineNumberCache;
        }
        return expr;
    }

    private <T> T tryParse(Supplier<T> supplier, String error) {
        final T node =  supplier.get();
        if (node == null) {
            throw error(error);
        }
        return node;
    }
}

// MARK: NodeScope
class NodeScope {
    final List<NodeStmt> statements;
    NodeScope(List<NodeStmt> s) { statements = List.copyOf(s); }
    public String toString() {
        return "{" + String.join("; ", 
            statements.stream()
            .map(x -> x.toString())
            .collect(Collectors.toList())) +
        "}";
    }
}

// MARK: NodeStatement
abstract class NodeStmt {
    static class If extends NodeStmt {
        final NodeExpr expr; final NodeScope succ, fail;
        If (NodeExpr e, NodeScope s, NodeScope f) { expr = e; succ = s; fail = f; }
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("if (%s) %s else %s", expr, succ, fail); } 
    }

    static class While extends NodeStmt {
        final NodeExpr expr; final NodeScope scope; 
        While(NodeExpr e, NodeScope s) { expr = e; scope = s; }
        public Object host(Visitor v) { return v.visit(this);  }
        public String toString() { return String.format("while (%s) %s", expr, scope); } 
    }

    static class ForEach extends NodeStmt {
        final NodeVar itr, list; final NodeScope scope; 
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("for (%s in %s) %s", itr, list, scope); } 
        ForEach(NodeVar i, NodeVar l, NodeScope s) { itr = i; list = l; scope = s; }
    }

    static class For extends NodeStmt {
        final Declare init; final NodeExpr cond; final Assign inc; final NodeScope scope;
        public Object host(Visitor v) {  return v.visit(this);  }
        public String toString() { return String.format("for (%s;%s;%s) %s", init, cond, inc, scope); }
        For(Declare d, NodeExpr c, Assign i, NodeScope s) { init = d; cond = c; inc = i; scope = s; }
    }

    static class Scope extends NodeStmt {
        final NodeScope scope;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(scope); } 
        Scope(NodeScope s) { scope = s; }
    }

    static class Declare extends NodeStmt {
        final NodeVar var; final NodeExpr expr;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("let %s = %s", var, expr); } 
        Declare(NodeVar q, NodeExpr e) { var = q; expr = e; }
    }
    
    static class Assign extends NodeStmt {
        final NodeTerm term; final AssignOp op; final NodeExpr expr;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s %s %s", term, op, expr); }
        Assign(AssignOp o, NodeTerm q, NodeExpr e) { op = o; term = q; expr = e; }
    }
    
    static class PropAssign extends NodeStmt {
        final NodeVar var, prop; final NodeExpr expr;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s.%s = %s", var, prop, expr); }
        PropAssign(NodeVar q, NodeVar m, NodeExpr e) { var = q; expr = e; prop = m; }
    }

    static class Expr extends NodeStmt {
        final NodeExpr expr;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return expr.toString(); } 
        Expr(NodeExpr e) { expr = e; }
    }

    static class Return extends NodeStmt {
        final NodeExpr expr;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return "return " + expr.toString(); } 
        Return(NodeExpr e) { expr = e; }
    }
    
    static class Break extends NodeStmt {
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return "break"; } 
    }
    
    static class Continue extends NodeStmt {
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return "continue"; } 
    }

    static class Function extends NodeStmt {
        final NodeVar var;
        final List<NodeParam> params;
        final NodeScope body;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return "function (WIP)"; } 
        Function(NodeVar e, List<NodeParam> a, NodeScope b) { var = e; params = a; body = b; }
    }

    static class TryCatch extends NodeStmt {
        final NodeScope _try, _catch, _finally;
        final NodeVar err;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return "trycatch (WIP)"; } 
        TryCatch(NodeScope t, NodeScope c, NodeVar e, NodeScope f) { 
            err = e; _try = t; _catch = c; _finally = f; 
        }
    }
    
    abstract public String toString();
    abstract Object host(Visitor visitor);
    interface Visitor {
        Object visit(Assign assignment);
        Object visit(PropAssign assignment);
        Object visit(Declare declaration);
        Object visit(Expr expression);
        Object visit(If statement);
        Object visit(While loop);
        Object visit(For loop);
        Object visit(ForEach loop);
        Object visit(Scope scope);
        Object visit(Break statement);
        Object visit(Continue statement);
        Object visit(Return statement);
        Object visit(Function definition);
        Object visit(TryCatch block);
    }
}

class NodeParam {
    final NodeVar param; final NodeExpr _default;
    NodeParam(NodeVar p, NodeExpr e) { param = p; _default = e; }
    public String toString() { return param.name + (_default == null ? "" : (" = " + _default)); }
}

// MARK: NodeExpression
enum BinaryOp {
/* Precedence */
    /* 8 */ Exponent("**"),
    /* 7 */ Multiply("*"), Divide("/"), Modulo("%"),
    /* 6 */ Add("+"), Subtract("-"),
    /* 5 */ ShiftLeft("<<"), ShiftRight(">>"),
    /* 4 */ Less("<"), LessEqual("<="), Greater(">"), GreaterEqual(">="),
    /* 3 */ Equal("=="), NotEqual("!="),
    /* 2 */ BitAnd("&"), BitOr("|"), BitXor("^"),
    /* 1 */ And("and"), Or("or");
    
    private final String symbol;
    private BinaryOp(String s) { symbol = s; }
    public String toString() { return symbol; }
}

enum UnaryOp { Increment, Decrement, Negate, Invert, Not }

enum AssignOp { AddEqual, SubEqual, MultiplyEqual, DivideEqual, ModEqual, AndEqual, OrEqual }

abstract class NodeExpr {
    public int lineNumber = 0;
    static class Binary extends NodeExpr {
        final NodeExpr lhs, rhs; final BinaryOp op;
        public <R> R host(Visitor<R> v) { return v.visit(this); }
        public String toString() { return String.format("%s %s %s", lhs, op, rhs); }
        Binary(BinaryOp o, NodeExpr l, NodeExpr r) { op = o; lhs = l; rhs = r; }
    }
    
    static class Unary extends NodeExpr {
        final NodeExpr val; final UnaryOp op;
        public <R> R host(Visitor<R> v) { return v.visit(this); }
        public String toString() { return String.format("%s%s", op, val); }
        Unary(UnaryOp o, NodeExpr v) { op = o; val = v; }
    }
    
    static class Term extends NodeExpr {
        final NodeTerm val;
        public <R> R host(Visitor<R> v) { return v.visit(this); }
        public String toString() { return val.toString(); } 
        Term(NodeTerm v) { val = v; }
    }

    abstract public String toString();
    abstract <R> R host(Visitor<R> v);
    interface Visitor<R> {
        R visit(Binary node);
        R visit(Unary node);
        R visit(Term node);
    }
}

// MARK: NodeTerm
interface NodeTerm {
    class Literal<R> implements NodeTerm {
        final R lit;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(lit); } 
        public Literal(R l) { lit = l; }
    }

    class ArrayLiteral implements NodeTerm {
        final List<NodeExpr> items;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(items); } 
        public ArrayLiteral(List<NodeExpr> i) { items = i; }
    }

    class ArrayAccess implements NodeTerm {
        final NodeVar array; final NodeExpr index;
        public Object host(Visitor v) { return v.visit(this); } 
        public String toString() { return String.format("%s[%s]", array, index); } 
        public ArrayAccess(NodeVar a, NodeExpr i) { array = a; index = i; }
    }
    
    class Variable implements NodeTerm {
        public final NodeVar var;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return var.name; } 
        public Variable(NodeVar v) { this.var = v; }
    }
    
    class PropAccess implements NodeTerm {
        final NodeVar object, prop;
        public Object host(Visitor visitor) { return visitor.visit(this); }
        public String toString() { return object.name + "." + prop.name; } 
        public PropAccess(NodeVar o, NodeVar p) { object = o; prop = p; }
    }

    public String toString();
    Object host(Visitor term);
    interface Visitor {
        Object visit(Literal<?> lit);
        Object visit(ArrayLiteral arr);
        Object visit(ArrayAccess acc);
        Object visit(Variable var);
        Object visit(PropAccess var);
    }
}

// MARK: NodeVariable
class NodeVar {
    final String name;
    public String toString() { return this.name; }
    NodeVar(String name) { this.name = name; }
}
