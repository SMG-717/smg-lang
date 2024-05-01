package smg.interpreter;

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
        if (!tryConsume(Token.OpenCurly)) return null; 
        
        final NodeScope scope = parseProgram();
        if (scope == null || scope.statements.isEmpty()) return null;

        tryConsume(Token.CloseCurly, "Expected '}'");
        return scope;
       
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

        // Jump statements
        else if ((statement = parseJumpStmt()) != null);
        
        // Function
        else if ((statement = parseFunction()) != null);
        
        // Try Catch
        else if ((statement = parseTryCatch()) != null);
        
        // Standalone scope
        else if ((statement = parseScopeStmt()) != null);
        
        // Assignments and Expressions
        // Note: Not sure if there's a better way to do this - SMG
        else {
            final NodeTerm start = parseTerm();
            if ((statement = parseAssignment(start)) == null) {
                final NodeExpr expr = parseExpression(new NodeExpr.Term(start), 0);
                statement = expr == null ? null : new NodeStmt.Expr(expr);
            }
        }

        return statement;
    }
    
    // Decl -> 'let' [Variable] '=' [Expr]
    private NodeStmt.Declare parseDecl() {
        if (!tryConsume(Token.Let)) return null; 

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

    // If -> 'if' [Expr] [Scope] ('else if' [Expr] [Scope])* ('else' [Scope])?
    private NodeStmt.If parseIf() {
        if (!tryConsume(Token.If)) return null; 

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

    // While -> 'while' [Expr] [Scope]
    private NodeStmt.While parseWhile() {
        if (!tryConsume(Token.While)) return null;
        
        return new NodeStmt.While(
            tryParse(() -> parseExpression(), "Expected condition."), 
            tryParse(() -> parseScope(), "Unparsable Scope.")
        );
    }

    
    // ForEach -> 'for' '(' [Qualifier] 'in' [Term] ')' [Scope]
    // ForLoop -> 'for' '(' ([Assign] | [Decl])? ';' [Expr]? ';' ([Assign] | [Expr])? ')' [Scope]
    private NodeStmt parseFor() {
        if (!tryConsume(Token.For)) return null;
        
        // Check if it is a valid for each
        tryConsume(Token.OpenParen, "Expected '('");
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
            increment = peek() != Token.CloseParen ? tryParse(() -> parseAssignment(parseTerm()), "Expected increment expression") : null;

            tryConsume(Token.CloseParen, "Expected ')'");
            final NodeScope scope = tryParse(() -> parseScope(), "Unparsable Scope.");
            return new NodeStmt.For(init, condition, increment, scope);
        }
    }

    private NodeStmt.Scope parseScopeStmt() {
        final NodeScope scope = parseScope();
        return scope == null ? null : new NodeStmt.Scope(scope);
    }
    
    private NodeStmt parseJumpStmt() {
        // Break -> 'break'
        if (tryConsume(Token.Break)) return new NodeStmt.Break();
        // Continue -> 'continue'
        else if (tryConsume(Token.Continue)) return new NodeStmt.Continue();
        // Return -> 'return' [Expr]?
        else if (tryConsume(Token.Return)) return new NodeStmt.Return(parseExpression());

        return null;
    }

    // Func -> 'define' [Variable] '(' ([Param] (',' [Param])*)? ')' [Scope]
    private NodeStmt.Function parseFunction() {
        if (!tryConsume(Token.Define)) return null;
         
        final NodeVar function = tryParse(() -> parseVariable(), "Expected function name");
        tryConsume(Token.OpenParen, "Expected '('");
        final List<NodeParam> params = new LinkedList<>();
        if (peek() != Token.CloseParen) {
            do {
                params.add(tryParse(() -> parseParam(), "Unexpected Token: " + peek().value));
            } 
            while (tryConsume(Token.Comma));
        }
        tryConsume(Token.CloseParen, "Expected ')'");
        return new NodeStmt.Function(function, params, tryParse(() -> parseScope(), "Expected function body"));
    }

    // Param -> [Variable] ('=' [Expr])
    private NodeParam parseParam() {
        final NodeVar param = parseVariable();

        return param == null ? null : 
            new NodeParam(param, !tryConsume(Token.EqualSign) ? null :
            tryParse(() -> parseExpression(), "Expected default paramter value")
        );      
    }
    
    // Try -> 'try' [Scope] ('catch' [Qualifier] [Scope])? ('finally' [Scope])?
    private NodeStmt.TryCatch parseTryCatch() {
        if (!tryConsume(Token.Try)) return null;

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

    /** It is the responsibility of the caller to parse the LHS for assignments */
    private NodeStmt.Assign parseAssignment(NodeTerm start) {
        
        final boolean assignType = start instanceof NodeTerm.ArrayAccess || 
            start instanceof NodeTerm.PropAccess || 
            start instanceof NodeTerm.Variable;

        if (!(assignType && peek().isAny(TokenType.AssignOperator))) return null;
        
        final Token op = tryConsume(TokenType.AssignOperator);
        final NodeExpr expr = tryParse(() -> parseExpression(), "Expected expression");
        return arthmeticAssignNode(op, start, expr);
    }

    private NodeStmt.Assign arthmeticAssignNode(Token op, NodeTerm lhs, NodeExpr rhs) {
        final AssignOp aop;
        if (op == Token.PlusEqual)              aop = AssignOp.AddEqual;
        else if (op == Token.SubtractEqual)     aop = AssignOp.SubEqual;
        else if (op == Token.MultiplyEqual)     aop = AssignOp.MultiplyEqual;
        else if (op == Token.ModEqual)          aop = AssignOp.ModEqual;
        else if (op == Token.DivideEqual)       aop = AssignOp.DivideEqual;
        else if (op == Token.AndEqual)          aop = AssignOp.AndEqual;
        else if (op == Token.OrEqual)           aop = AssignOp.OrEqual;
        else {
            throw error("Unsupported assignent operation: " + op);
        }

        return new NodeStmt.Assign(aop, lhs, rhs);
    }

    // MARK: Parse Expression
    private NodeExpr parseExpression() {
        cacheLineNumber();
        return lineNumerise(parseExpression(new NodeExpr.Term(parseTerm()), 0));
    }
    
    private NodeExpr parseExpression(NodeExpr left, int prec) {
        cacheLineNumber();
        NodeExpr expr = left;
        while (peek().isAny(TokenType.BinaryArithmetic) && peek().precedence >= prec) {
            final Token op = consume();
            NodeExpr right = new NodeExpr.Term(parseTerm());

            while (peek().isAny(TokenType.BinaryArithmetic) && (
                (peek().precedence > op.precedence) || 
                (peek().precedence >= op.precedence && peek().rightassoc))) {
                right = parseExpression(right, op.precedence + (peek().precedence > op.precedence ? 1 : 0));
            }
            expr = arthmeticNode(op, expr, right);
        }

        return lineNumerise(expr);
    }

    private NodeExpr.Binary arthmeticNode(Token op, NodeExpr lhs, NodeExpr rhs) {
        final BinaryOp bop;
        if (op == Token.Plus)               bop = BinaryOp.Add;
        else if (op == Token.Hyphen)        bop = BinaryOp.Subtract;
        else if (op == Token.Asterisk)      bop = BinaryOp.Multiply;
        else if (op == Token.ForwardSlash)  bop = BinaryOp.Divide;
        else if (op == Token.Percent)       bop = BinaryOp.Modulo;
        else if (op == Token.Caret)         bop = BinaryOp.Exponent;
        else if (op == Token.Greater)       bop = BinaryOp.Greater;
        else if (op == Token.GreaterEqual)  bop = BinaryOp.GreaterEqual;
        else if (op == Token.Less)          bop = BinaryOp.Less;
        else if (op == Token.LessEqual)     bop = BinaryOp.LessEqual;
        else if (op == Token.Equals)        bop = BinaryOp.Equal;
        else if (op == Token.NotEquals)     bop = BinaryOp.NotEqual;
        else if (op == Token.And)           bop = BinaryOp.And;
        else if (op == Token.Or)            bop = BinaryOp.Or;
        else {
            throw error("Unsupported binary arithmetic operation: " + op);
        }

        return new NodeExpr.Binary(bop, lhs, rhs);
    }

    
    // MARK: Parse Term
    private NodeTerm parseTerm() {
        NodeTerm term;

        if ((term = parseTermExpr()) == null);
        else if ((term = parseArrayLiteral()) == null);
        else if ((term = parseMapLiteral()) == null);
        else if ((term = parseUnaryExpr()) == null);
        else if ((term = parseTermVariable()) == null);
        else if ((term = parseLiteral()) == null);
        else {
            return null;
        }
        
        // Primary term parsed. It could be followed by any number of other items
        // to modify it.
        NodeTerm moddedTerm = null;
        while (peek() != Token.EOT) {
            if ((moddedTerm = parseArrayAccess(term)) == null);
            else if ((moddedTerm = parsePropAccess(term)) == null);
            else if ((moddedTerm = parseCall(term)) == null);
            else if ((moddedTerm = parseCast(term)) == null);
            
            if (moddedTerm == null) return term;

            term = moddedTerm;
        }

        return term;
    }

    // Term.Expr -> '(' [Expr] ')'
    private NodeTerm.Expr parseTermExpr() {
        if (!tryConsume(Token.OpenParen)) return null;

        final NodeTerm.Expr term = new NodeTerm.Expr(
            tryParse(() -> parseExpression(), "Expected expression.")
        );
        tryConsume(Token.CloseParen);
        return term;
    }

    // Term.ArrayLiteral -> '[' ([Expr] (',' [Expr])*)? ']'
    private NodeTerm.ArrayLiteral parseArrayLiteral() {
        if (!tryConsume(Token.OpenSquare)) return null;

        final NodeTerm.ArrayLiteral arr = new NodeTerm.ArrayLiteral(new LinkedList<>());
        if (tryConsume(Token.CloseSquare)) return arr;
        
        do {
            arr.items.add(tryParse(() -> parseExpression(), "Expected expression"));
        } 
        while (tryConsume(Token.Comma));

        tryConsume(Token.CloseSquare, "Expected ']'");
        return arr;
    }
    
    // Term.MapLiteral
    private NodeTerm.MapLiteral parseMapLiteral() {
        if (!tryConsume(Token.OpenCurly)) return null;

        final NodeTerm.MapLiteral arr = new NodeTerm.MapLiteral(new LinkedList<>());
        if (tryConsume(Token.CloseCurly)) return arr;
        
        do {
            final NodeVar key = tryParse(() -> parseVariable(), "Expected variable");
            tryConsume(Token.Colon, "Expected ':");
            final NodeExpr value = tryParse(() -> parseExpression(), "Expected expression");
            arr.items.add(new NodeMapEntry(key, value));
        } 
        while (tryConsume(Token.Comma));

        tryConsume(Token.CloseCurly, "Expected ']'");
        return arr;
    }

    // Term.UnaryExpr
    private NodeTerm.UnaryExpr parseUnaryExpr() {
        final Token uop;
        if ((uop = tryConsume(TokenType.UnaryArithmetic)) == null) return null;

        return unaryArthmeticNode(uop, tryParse(() -> parseTerm(), "Expected atomic expression"));
    }
    
    
    private NodeTerm.UnaryExpr unaryArthmeticNode(Token op, NodeTerm val) {
        final UnaryOp uop;
        if (op == Token.Hyphen)         uop = UnaryOp.Negate;
        else if (op == Token.Tilde)     uop = UnaryOp.Invert;
        else if (op == Token.Not)       uop = UnaryOp.Not;
        else if (op == Token.Exclaim)   uop = UnaryOp.Not;
        else {
            throw error("Unsupported unary arithmetic operation: " + op);
        }

        return new NodeTerm.UnaryExpr(uop, val);
    }

    // Term.ArrayAccess
    private NodeTerm.ArrayAccess parseArrayAccess(NodeTerm term) {
        if (!tryConsume(Token.OpenSquare)) return null;

        final NodeExpr expr = tryParse(() -> parseExpression(), "Expected array index expression.");
        tryConsume(Token.CloseSquare, "Expected ']'");

        return new NodeTerm.ArrayAccess(term, expr);
    }

    // Term.PropAccess
    private NodeTerm.PropAccess parsePropAccess(NodeTerm term) {
        if (!tryConsume(Token.Period)) return null;

        return new NodeTerm.PropAccess(term, 
            tryParse(() -> parseVariable(), "Expected array index expression.")
        );
    }

    // Term.Call
    private NodeTerm.Call parseCall(NodeTerm term) {
        if (!tryConsume(Token.OpenParen)) return null;

        final NodeTerm.Call call = new NodeTerm.Call(term, new LinkedList<>());
        if (tryConsume(Token.CloseParen)) return call;
        
        do {
            call.args.add(tryParse(() -> parseExpression(), "Expected expression"));
        } 
        while (tryConsume(Token.Comma));

        tryConsume(Token.CloseParen, "Expected ')'");
        return call;
    }

    // Term.Cast
    private NodeTerm.Cast parseCast(NodeTerm term) {
        if (!tryConsume(Token.As)) return null;

        return new NodeTerm.Cast(term, 
            tryParse(() -> parseType(), "Expected array index expression.")
        );
    }

    private NodeType parseType() {
        final Token token = tryConsume(TokenType.CastType);
        return token == null ? null : new NodeType(token.value);
    }

    // Term.Variable
    private NodeTerm.Variable parseTermVariable() {
        final NodeVar var = parseVariable();
        return var == null ? null : new NodeTerm.Variable(var);
    }
    
    private NodeVar parseVariable() {
        if (!peek().isAny(TokenType.Qualifier)) {
            return null;   
        }
        
        return new NodeVar(consume().value);
    }
    
    // Term.Literal<?>
    private NodeTerm.Literal<?> parseLiteral() {
        if (tryConsume(Token.Empty)) 
            return new NodeTerm.Literal<Void>(null);
        else if (peek().isAny(TokenType.BooleanLiteral)) 
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

        return null;
    }

    // MARK: Token Handling
    private Token peek() {
        return peek(0);
    }

    private Token peek(int offset) {
        while (cache.size() <= offset) {
            cache.add(tokeniser.nextToken());
        }
        return cache.get(offset);
    }
    
    private Token consume() {
        final Token consumable = cache.size() > 0 ? cache.remove(0) : tokeniser.nextToken();
        if (!consumable.isAny(TokenType.StringLiteral)) {
            lineNumber += consumable.value.chars().filter(i -> i == '\n').count(); // Possible improvement
        }
        return consumable;
    }

    private boolean tryConsume(Token token, boolean skipBlank) {
        final boolean success;
        if (success = peek() == token) {
            consume();
            if (skipBlank) skipBlank();
        }
        return success;
    }

    private Token tryConsume(boolean skipBlank, TokenType... types) {
        if (!peek().isAny(types) || peek() == Token.EOT) return null; 
        
        final Token token = consume();
        if (skipBlank) skipBlank();
        return token;
    }

    private boolean tryConsume(Token token) {
        return tryConsume(token, true);
    }

    private Token tryConsume(TokenType... types) {
        return tryConsume(true, types);
    }

    private void tryConsume(Token token, String message) {
        if (!tryConsume(token)) throw error(message);
    }

    private void skipBlank() {
        while (peek() == Token.Newline || peek().isAny(TokenType.Comment)) consume();
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
enum AssignOp { 
    AddEqual, SubEqual, 
    MultiplyEqual, DivideEqual, ModEqual, 
    AndEqual, OrEqual 
}

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
    Exponent("**"),
    Multiply("*"), Divide("/"), Modulo("%"),
    Add("+"), Subtract("-"),
    ShiftLeft("<<"), ShiftRight(">>"),
    Less("<"), LessEqual("<="), Greater(">"), GreaterEqual(">="),
    Equal("=="), NotEqual("!="),
    BitAnd("&"), BitOr("|"), BitXor("^"),
    And("and"), Or("or");
    
    private final String symbol;
    private BinaryOp(String s) { symbol = s; }
    public String toString() { return symbol; }
}

abstract class NodeExpr {
    public int lineNumber = 0;
    static class Binary extends NodeExpr {
        final NodeExpr lhs, rhs; final BinaryOp op;
        public <R> R host(Visitor<R> v) { return v.visit(this); }
        public String toString() { return String.format("%s %s %s", lhs, op, rhs); }
        Binary(BinaryOp o, NodeExpr l, NodeExpr r) { op = o; lhs = l; rhs = r; }
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
        R visit(Term node);
    }
}

// MARK: NodeTerm
enum UnaryOp { 
    Increment, Decrement, 
    Negate, Invert, Not 
}

interface NodeTerm {
    static class Expr implements NodeTerm {
        final NodeExpr expr;
        public Expr(NodeExpr e) { expr = e; }
        public Object host(Visitor v) { return v.visit(this); }
    }

    static class ArrayLiteral implements NodeTerm {
        final List<NodeExpr> items;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(items); } 
        public ArrayLiteral(List<NodeExpr> i) { items = i; }
    }

    static class MapLiteral implements NodeTerm {
        final List<NodeMapEntry> items;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(items); } 
        public MapLiteral(List<NodeMapEntry> i) { items = i; }
    }

    static class UnaryExpr implements NodeTerm {
        final NodeTerm val; final UnaryOp op;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s%s", op, val); }
        UnaryExpr(UnaryOp o, NodeTerm v) { op = o; val = v; }
    }

    static class ArrayAccess implements NodeTerm {
        final NodeTerm array; final NodeExpr index;
        public Object host(Visitor v) { return v.visit(this); } 
        public String toString() { return String.format("%s[%s]", array, index); } 
        public ArrayAccess(NodeTerm a, NodeExpr i) { array = a; index = i; }
    }
    
    static class Variable implements NodeTerm {
        public final NodeVar var;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return var.name; } 
        public Variable(NodeVar v) { this.var = v; }
    }
    
    static class PropAccess implements NodeTerm {
        final NodeTerm object; final NodeVar prop;
        public Object host(Visitor visitor) { return visitor.visit(this); }
        public String toString() { return object.toString() + "." + prop.name; } 
        public PropAccess(NodeTerm o, NodeVar p) { object = o; prop = p; }
    }

    static class Literal<R> implements NodeTerm {
        final R lit;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(lit); } 
        public Literal(R l) { lit = l; }
    }
    
    static class Call implements NodeTerm {
        final NodeTerm f; final List<NodeExpr> args;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s(%s)", f, String.join(", ", args.stream()
            .map(a -> a.toString())
            .collect(Collectors.toList()))
        ); } 
        public Call(NodeTerm t, List<NodeExpr> e) { f = t; args = e; }
    }

    static class Cast implements NodeTerm {
        final NodeTerm object; final NodeType type;
        public Object host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s as %s", object, type); } 
        public Cast(NodeTerm o, NodeType t) { object = o; type = t; }
    }

    public String toString();
    Object host(Visitor term);
    interface Visitor {
        Object visit(Expr expr);
        Object visit(ArrayLiteral arr);
        Object visit(MapLiteral map);
        Object visit(UnaryExpr expr);
        Object visit(ArrayAccess acc);
        Object visit(Variable var);
        Object visit(PropAccess var);
        Object visit(Literal<?> lit);
        Object visit(Call call);
        Object visit(Cast cast);
    }
}

class NodeMapEntry {
    final NodeVar key; final NodeExpr value;
    NodeMapEntry(NodeVar p, NodeExpr e) { key = p; value = e; }
    public String toString() { return key.name + (value == null ? "" : (": " + value)); }
}

class NodeType {
    final String type;
    public String toString() { return type; }
    NodeType(String t) { type = t; }
}

class NodeVar {
    final String name;
    public String toString() { return this.name; }
    NodeVar(String name) { this.name = name; }
}
