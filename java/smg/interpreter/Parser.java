package smg.interpreter;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class Parser {
    
    private final List<Token> cache;
    private final Tokeniser tokeniser;
    private NodeProgram root = null;
    private int line = 1;

    public Parser(Tokeniser t) {
        tokeniser = t;
        cache = new LinkedList<>();
    }
    
    public Parser(String input) {
        this(new Tokeniser(input + Token.EOF));
    }

    public NodeProgram parse() {
        tokeniser.reset();
        line = 1;
        skipBlank();
        root = parseProgram();

        if (peek() != Token.EOT) {
            throw error("Unexpected token at end of program: " + peek().value);
        }
        return root;
    }
    
    // MARK: Parse Scope
    // Scope -> '{' [Program] '}'
    private NodeScope parseScope() {
        if (!tryConsume(Token.OpenCurly)) return null; 
        
        final NodeScope scope = new NodeScope(parseProgram().stmts);
        tryConsume(Token.CloseCurly, "Expected '}'");
        return scope;
    }

    // Program -> ([StmtTerm]* [Statement]?)*
    private NodeProgram parseProgram() {
        final NodeProgram scope = new NodeProgram(new LinkedList<>());
        
        do {
            // [StmtTerm]*
            while (tryConsume(TokenType.StatementTerminator) != null);
            
            // [Statement]?
            NodeStmt stmt;
            if ((stmt = parseStatement()) == null) break;
            scope.stmts.add(stmt);
        } 
        while (peek() != Token.EOT);
        return scope;
    }
    
    // MARK: Parse Statement
    
    // Statement -> [Decl] | [If] | [While] | [ForEach] | [ForLoop] | [Scope] | 
    //   [Try] | [Func] | [Break] | [Continue] | [Return] |
    //   [Assign] | [Expr]
    private NodeStmt parseStatement() {
        final NodeTerm term; NodeStmt stmt;

        // Declaration
        if ((stmt = parseDecl()) != null);
        // If statement
        else if ((stmt = parseIf()) != null);
        // While loop
        else if ((stmt = parseWhile()) != null);
        // For loop
        else if ((stmt = parseFor()) != null);
        // Jump statements
        else if ((stmt = parseJumpStmt()) != null);
        // Function
        else if ((stmt = parseFunction()) != null);
        // Try Catch
        else if ((stmt = parseTryCatch()) != null);
        // Standalone scope
        else if ((stmt = parseScopeStmt()) != null);
        // Assignments
        else if ((stmt = parseAssign(term = parseTerm())) != null);
        // Expressions
        else if ((stmt = parseStmtExpr(term)) != null);

        // By this point we should have parsed a statement. 
        // It could still be null if no statement can be parsed.
        return stmt;
    }
    
    // Decl -> 'let' [Variable] '=' [Expr]
    private NodeStmt.Declare parseDecl() {
        if (!tryConsume(Token.Let)) return null;

        return new NodeStmt.Declare(
            tryParse(parseVariable(), "Expected qualifier: " + peek().value), 
            !tryConsume(Token.EqualSign) ? NodeExpr.NULL :
                tryParse(parseExpr(), "Expected expression.")                
        );
    }

    // If -> 'if' [Expr] [Scope] ('else if' [Expr] [Scope])* ('else' [Scope])?
    private NodeStmt.If parseIf() {
        if (!tryConsume(Token.If)) return null; 
        final NodeExpr expr; final NodeScope scope, scopeElse;

        expr = tryParse(parseExpr(), "Expected condition.");
        scope = tryParse(parseScope(), "Unparsable Scope.");
        if (tryConsume(Token.Else)) {
            if (peek() == Token.If) scopeElse = new NodeScope(List.of(parseIf()));
            else scopeElse = tryParse(parseScope(), "Expected else block.");
        }
        else scopeElse = null;
        return new NodeStmt.If(expr, scope, scopeElse);
    }

    // While -> 'while' [Expr] [Scope]
    private NodeStmt.While parseWhile() {
        if (!tryConsume(Token.While)) return null;
        
        return new NodeStmt.While(
            tryParse(parseExpr(), "Expected condition."), 
            tryParse(parseScope(), "Unparsable Scope.")
        );
    }
    
    // ForEach -> 'for' '(' [Qualifier] 'in' [Term] ')' [Scope]
    // ForLoop -> 'for' '(' ([Assign] | [Decl])? ';' [Expr]? ';' ([Assign] | [Expr])? ')' [Scope]
    private NodeStmt parseFor() {
        if (!tryConsume(Token.For)) return null;
        
        
        // For Each
        tryConsume(Token.OpenParen, "Expected '('");
        if (peek().isAny(TokenType.Qualifier) && peekNonBlank() == Token.In) {
            final String itr; final NodeTerm list;

            itr = parseVariable();
            tryConsume(Token.In);
            list = tryParse(parseTerm(), "Expected variable.");
            tryConsume(Token.CloseParen, "Expected ')'");

            return new NodeStmt.ForEach(itr, list, 
                tryParse(parseScope(), "Unparsable Scope.")
            );
        }

        // Normal For
        else {
            final NodeStmt.Declare init; final NodeExpr cond; final NodeStmt inc;

            init = peek() == Token.SemiColon ? null : 
                tryParse(parseDecl(), "Expected declaration.");
            tryConsume(Token.SemiColon, "Expected ';'");

            cond = peek() == Token.SemiColon ? null : 
                tryParse(parseExpr(), "Expected boolean expression");
            tryConsume(Token.SemiColon, "Expected ';'");

            if (peek() != Token.CloseParen) {
                NodeStmt stmt; NodeTerm term;
                if ((stmt = parseAssign(term = parseTerm())) != null);
                else if ((stmt = parseStmtExpr(term)) != null);
                else throw error("Expected an increment assignment or expression");

                inc = stmt;
            }
            else inc = null;

            tryConsume(Token.CloseParen, "Expected ')'");
            return new NodeStmt.For(init, cond, inc, 
                tryParse(parseScope(), "Unparsable Scope.")
            );
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
        else if (tryConsume(Token.Return)) return new NodeStmt.Return(parseExpr());

        return null;
    }

    // Func -> 'define' [Variable] [ParamList] [Scope]
    private NodeStmt.Function parseFunction() {

        // If it has a qualifier, it is a proper function and not a lambda
        if (peek() != Token.Function || !peekNonBlank(1).isAny(TokenType.Qualifier)) return null;

        tryConsume(Token.Function);
        final String name = parseVariable();

        final List<NodeParam> params = parseParamList();
        return new NodeStmt.Function(name, params, tryParse(parseScope(), "Expected function body"));
    }

    // ParamList -> '(' ([Param] (',' [Param])*)? ')'
    private List<NodeParam> parseParamList() {
        final List<NodeParam> params = new LinkedList<>();
        tryConsume(Token.OpenParen, "Expected '('");
        if (peek() != Token.CloseParen) {
            do {
                params.add(tryParse(parseParam(), "Unexpected Token: " + peek().value));
            } 
            while (tryConsume(Token.Comma));
        }
        tryConsume(Token.CloseParen, "Expected ')'");
        return params;
    }

    // Param -> [Variable] ('=' [Expr])
    private NodeParam parseParam() {
        final String param = parseVariable();

        return param == null ? null : 
            new NodeParam(param, !tryConsume(Token.EqualSign) ? null :
            tryParse(parseExpr(), "Expected default paramter value")
        );      
    }
    
    // Try -> 'try' [Scope] ('catch' [Qualifier] [Scope])? ('finally' [Scope])?
    private NodeStmt.TryCatch parseTryCatch() {
        if (!tryConsume(Token.Try)) return null;

        final NodeScope tryScope, catchScope, finallyScope;
        final String err;

        tryScope = tryParse(parseScope(), "Expected try block.");
        if (tryConsume(Token.Catch)) {
            if (tryConsume(Token.OpenParen)) {
                err = parseVariable();
                tryConsume(Token.CloseParen, "Expected ')'");
            }
            else {
                err = parseVariable();
            }
            catchScope = tryParse(parseScope(), "Expected catch block.");
        }
        else {
            catchScope = null; err = null;
        }

        finallyScope = tryConsume(Token.Finally) ? tryParse(parseScope(), "Expected finally block.") : null;
        return new NodeStmt.TryCatch(tryScope, catchScope, err, finallyScope);
    }

    /** It is the responsibility of the caller to parse the LHS for assignments */
    private NodeStmt.Assign parseAssign(NodeTerm term) {
        
        final boolean assignType = term instanceof NodeTerm.ArrayAccess || 
            term instanceof NodeTerm.PropAccess || 
            term instanceof NodeTerm.Variable;

        if (!(assignType && peek().isAny(TokenType.AssignOperator))) return null;
        
        final Token op = tryConsume(TokenType.AssignOperator);
        final NodeExpr expr = tryParse(parseExpr(), "Expected expression");
        return arthmeticAssignNode(op, term, expr);
    }

    private NodeStmt.Expr parseStmtExpr(NodeTerm term) {
        return term == null ? null : new NodeStmt.Expr(parseExpression(term, 0));
    }

    private NodeStmt.Assign arthmeticAssignNode(Token op, NodeTerm lhs, NodeExpr rhs) {
        final AssignOp aop;
        if (op == Token.EqualSign)              aop = AssignOp.AssignEqual;
        else if (op == Token.PlusEqual)         aop = AssignOp.AddEqual;
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
    private NodeExpr parseExpr() {
        final NodeExpr expr; final NodeTerm term;
        if (peek() == Token.Function) {
            expr = parseLambda();
        }
        else if ((term = parseTerm()) != null) {
            expr = parseExpression(term, 0);
        }
        else {
            return null;
        }

        return expr;
    }

    private NodeExpr.Lambda parseLambda() {
        final int currentline = line;
        if (!tryConsume(Token.Function)) return null;
        tryConsume(Token.OpenParen, "Expected '('");
        final List<NodeParam> params = new LinkedList<>();
        if (peek() != Token.CloseParen) {
            do {
                params.add(tryParse(parseParam(), "Unexpected Token: " + peek().value));
            } 
            while (tryConsume(Token.Comma));
        }
        tryConsume(Token.CloseParen, "Expected ')'");

        final NodeScope lambody;
        if (peek() == Token.OpenCurly) {
            lambody = tryParse(parseScope(), "Expected function body or expression");
        }
        else {
            lambody = new NodeScope(List.of(
                new NodeStmt.Return(tryParse(parseExpr(), "Expected function body or expression"))
            ));
        }

        return new NodeExpr.Lambda(params, lambody, currentline);
    }
    
    private NodeExpr parseExpression(NodeTerm left, int prec) {
        final int currentline = line;
        
        NodeTerm expr = left;
        while (peek().isAny(TokenType.BinaryArithmetic) && peek().precedence >= prec) {
            final Token op = consume();
            NodeTerm right = parseTerm();

            while (peek().isAny(TokenType.BinaryArithmetic) && (
                (peek().precedence > op.precedence) || 
                (peek().precedence >= op.precedence && peek().rightassoc))) {
                right = new NodeTerm.Expr(parseExpression(right, op.precedence + (peek().precedence > op.precedence ? 1 : 0)));
            }
            expr = new NodeTerm.Expr(arthmeticNode(op, expr, right));
        }

        return new NodeExpr.Term(expr, currentline);
    }

    private NodeExpr.Binary arthmeticNode(Token op, NodeTerm lhs, NodeTerm rhs) {
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

        return new NodeExpr.Binary(bop, lhs, rhs, line);
    }

    
    // MARK: Parse Term
    private NodeTerm parseTerm() {
        NodeTerm term;

        if ((term = parseTermExpr()) != null);
        else if ((term = parseArrayLiteral()) != null);
        else if ((term = parseMapLiteral()) != null);
        else if ((term = parseUnaryExpr()) != null);
        else if ((term = parseTermVariable()) != null);
        else if ((term = parseLiteral()) != null);
        else {
            return null;
        }
        
        // Primary term parsed. It could be followed by any number of other items
        // to modify it.
        NodeTerm moddedTerm = null;
        while (peek() != Token.EOT) {
            if ((moddedTerm = parseArrayAccess(term)) != null);
            else if ((moddedTerm = parsePropAccess(term)) != null);
            else if ((moddedTerm = parseCall(term)) != null);
            else if ((moddedTerm = parseCast(term)) != null);
            
            if (moddedTerm == null) return term;

            term = moddedTerm;
        }

        return term;
    }

    // Term.Expr -> '(' [Expr] ')'
    private NodeTerm parseTermExpr() {
        if (!tryConsume(Token.OpenParen)) return null;

        final NodeTerm.Expr term = new NodeTerm.Expr(
            tryParse(parseExpr(), "Expected expression.")
        );
        tryConsume(Token.CloseParen);

        if (term.expr instanceof NodeExpr.Term) {
            return ((NodeExpr.Term) term.expr).val;
        }
        return term;
    }

    // Term.ArrayLiteral -> '[' ([Expr] (',' [Expr])*)? ']'
    private NodeTerm.ArrayLiteral parseArrayLiteral() {
        if (!tryConsume(Token.OpenSquare)) return null;

        final NodeTerm.ArrayLiteral arr = new NodeTerm.ArrayLiteral(new LinkedList<>());
        if (tryConsume(Token.CloseSquare)) return arr;
        
        do {
            final NodeExpr expr = parseExpr();
            if (expr == null) break;

            arr.items.add(expr);
        } 
        while (tryConsume(Token.Comma));
        skipBlank();
        tryConsume(Token.CloseSquare, "Expected ']'");
        return arr;
    }
    
    // Term.MapLiteral
    private NodeTerm.MapLiteral parseMapLiteral() {
        if (!tryConsume(Token.OpenCurly)) return null;

        final NodeTerm.MapLiteral arr = new NodeTerm.MapLiteral(new LinkedList<>());
        if (tryConsume(Token.CloseCurly)) return arr;
        
        do {
            final String key = parseVariable();
            if (key == null) break;
            
            tryConsume(Token.Colon, "Expected ':");
            final NodeExpr value = tryParse(parseExpr(), "Expected expression");
            arr.items.add(new NodeMapEntry(key, value));
        } 
        while (tryConsume(Token.Comma));
        skipBlank();
        tryConsume(Token.CloseCurly, "Expected '}'");
        return arr;
    }

    // Term.UnaryExpr
    private NodeTerm.UnaryExpr parseUnaryExpr() {
        final Token uop;
        if ((uop = tryConsume(TokenType.UnaryArithmetic)) == null) return null;

        return unaryArthmeticNode(uop, tryParse(parseTerm(), "Expected atomic expression"));
    }
    
    
    private NodeTerm.UnaryExpr unaryArthmeticNode(Token op, NodeTerm val) {
        final UnaryOp uop;
        if (op == Token.Hyphen)         uop = UnaryOp.Negate;
        else if (op == Token.Tilde)     uop = UnaryOp.Invert;
        else if (op == Token.Not || op == Token.Exclaim)   uop = UnaryOp.Not;
        else {
            throw error("Unsupported unary arithmetic operation: " + op);
        }

        return new NodeTerm.UnaryExpr(uop, val);
    }

    // Term.ArrayAccess
    private NodeTerm.ArrayAccess parseArrayAccess(NodeTerm term) {
        if (!tryConsume(Token.OpenSquare)) return null;

        final NodeExpr expr = tryParse(parseExpr(), "Expected array index expression.");
        tryConsume(Token.CloseSquare, "Expected ']'");

        return new NodeTerm.ArrayAccess(term, expr);
    }

    // Term.PropAccess
    private NodeTerm.PropAccess parsePropAccess(NodeTerm term) {
        if (!tryConsume(Token.Period)) return null;

        return new NodeTerm.PropAccess(term, 
            tryParse(parseVariable(), "Expected array index expression.")
        );
    }

    // Term.Call
    private NodeTerm.Call parseCall(NodeTerm term) {
        if (!tryConsume(Token.OpenParen)) return null;

        final NodeTerm.Call call = new NodeTerm.Call(term, new LinkedList<>());
        if (tryConsume(Token.CloseParen)) return call;
        
        do {
            call.args.add(tryParse(parseExpr(), "Expected expression"));
        } 
        while (tryConsume(Token.Comma));

        tryConsume(Token.CloseParen, "Expected ')'");
        return call;
    }

    // Term.Cast
    private NodeTerm.Cast parseCast(NodeTerm term) {
        if (!tryConsume(Token.As)) return null;

        return new NodeTerm.Cast(term, 
            tryParse(parseType(), "Expected array index expression.")
        );
    }

    private NodeType parseType() {
        final Token token = tryConsume(TokenType.CastType);
        return token == null ? null : new NodeType(token.value);
    }

    // Term.Variable
    private NodeTerm.Variable parseTermVariable() {
        final String var = parseVariable();
        return var == null ? null : new NodeTerm.Variable(var);
    }
    
    private String parseVariable() {
        return peek().isAny(TokenType.Qualifier) ? consume().value : null;
    }
    
    // Term.Literal<?>
    private NodeTerm.Literal<?> parseLiteral() {
        if (tryConsume(Token.Null)) 
            return new NodeTerm.Literal<Void>(null);
        else if (peek().isAny(TokenType.BooleanLiteral)) 
            return new NodeTerm.Literal<Boolean>(consume().equals(Token.True));
        else if (peek().isAny(TokenType.StringLiteral))
            return new NodeTerm.Literal<String>(consume().value);
        else if (peek().isAny(TokenType.NumberLiteral)) {
            final String repr = consume().value;
            try {
                return new NodeTerm.Literal<Long>(Long.parseLong(repr));
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
            line += consumable.value.chars().filter(i -> i == '\n').count(); // Possible improvement
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

    private Token peekNonBlank() {
        return peekNonBlank(1);
    }

    private Token peekNonBlank(int count) {
        int ahead = 1;
        while (count > 0) {
            while (peek(ahead) == Token.Newline || peek(ahead).isAny(TokenType.Comment)) ahead += 1;
            count -= 1;
        }
        return peek(ahead);
    }

    // MARK: Helpers
    public NodeProgram getRoot() {
        return root;
    }

    private RuntimeException error(String message) {
        return new RuntimeException(message + " (line: " + line + ")");
    }

    private <T> T tryParse(T node, String error) {
        if (node == null) {
            throw error(error);
        }
        return node;
    }
}

// MARK: NodeScope
class NodeProgram {
    final List<NodeStmt> stmts;
    NodeProgram(List<NodeStmt> s) { stmts = s; }
    public String toString() {
        return String.join("", 
            stmts.stream()
            .map(x -> x.toString() + ";\n")
            .collect(Collectors.toList()));
    }
}

class NodeScope {
    final List<NodeStmt> stmts;
    NodeScope(List<NodeStmt> s) { stmts = s; }
    public String toString() {
        return "{\n" + 
            String.join("", 
                stmts.stream()
                .map(x -> x.toString() + ";\n")
                .collect(Collectors.toList()))
            .indent(2)
        + "}";
    }
}

// MARK: NodeStatement
enum AssignOp { 
    AssignEqual("="), AddEqual("+="), SubEqual("-="), 
    MultiplyEqual("*="), DivideEqual("/="), ModEqual("%="), 
    AndEqual("&="), OrEqual("|=");
    private final String value;
    private AssignOp(String v) { value = v; }
    public String toString() { return value; };
}

abstract class NodeStmt {
    static class If extends NodeStmt {
        final NodeExpr expr; final NodeScope succ, fail;
        If (NodeExpr e, NodeScope s, NodeScope f) { expr = e; succ = s; fail = f; }
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("if (%s) %s", expr, succ, 
               fail == null ? "" : String.format("\nelse %s", fail)
            ); 
        } 
    }

    static class While extends NodeStmt {
        final NodeExpr expr; final NodeScope scope; 
        While(NodeExpr e, NodeScope s) { expr = e; scope = s; }
        public void host(Visitor v) { v.visit(this);  }
        public String toString() { return String.format("while (%s) %s", expr, scope); } 
    }

    static class ForEach extends NodeStmt {
        final String itr; final NodeTerm list; final NodeScope scope; 
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return String.format("for (%s in %s) %s", itr, list, scope); } 
        ForEach(String i, NodeTerm l, NodeScope s) { itr = i; list = l; scope = s; }
    }

    static class For extends NodeStmt {
        final Declare init; final NodeExpr cond; final NodeStmt inc; final NodeScope scope;
        public void host(Visitor v) { v.visit(this);  }
        public String toString() { return String.format("for (%s;%s;%s) %s", init, cond, inc, scope); }
        For(Declare d, NodeExpr c, NodeStmt i, NodeScope s) { init = d; cond = c; inc = i; scope = s; }
    }

    static class Scope extends NodeStmt {
        final NodeScope scope;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return scope.toString(); } 
        Scope(NodeScope s) { scope = s; }
    }

    static class Declare extends NodeStmt {
        final String var; final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return String.format("let %s = %s", var, expr); } 
        Declare(String q, NodeExpr e) { var = q; expr = e; }
    }
    
    static class Assign extends NodeStmt {
        final NodeTerm term; final AssignOp op; final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return String.format("%s %s %s", term, op, expr); }
        Assign(AssignOp o, NodeTerm q, NodeExpr e) { op = o; term = q; expr = e; }
    }

    static class Expr extends NodeStmt {
        final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return expr.toString(); } 
        Expr(NodeExpr e) { expr = e; }
    }

    static class Return extends NodeStmt {
        final NodeExpr expr;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return "return" + (expr == null ? "" : " " + expr);} 
        Return(NodeExpr e) { expr = e; }
    }
    
    static class Break extends NodeStmt {
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return "break"; } 
    }
    
    static class Continue extends NodeStmt {
        public void host(Visitor v) { v.visit(this); }
        public String toString() { return "continue"; } 
    }

    static class Function extends NodeStmt {
        final String name;
        final List<NodeParam> params;
        final NodeScope body;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("function %s (%s) %s", 
                name,  
                String.join(", ", 
                    params.stream()
                    .map(p -> p.toString())
                    .collect(Collectors.toList())), 
                body); 
        } 
        Function(String e, List<NodeParam> a, NodeScope b) { name = e; params = a; body = b; }
    }

    static class TryCatch extends NodeStmt {
        final NodeScope _try, _catch, _finally;
        final String err;
        public void host(Visitor v) { v.visit(this); }
        public String toString() { 
            return String.format("try %s%s%s",
                 _try, 
                _catch == null ? "" : String.format("\ncatch %s%s",
                    err == null ? "" : String.format("(%s) ", err), _catch    
                ),
                _finally == null ? "" : String.format("\nfinally %s", _finally)
            ); 
        } 
        TryCatch(NodeScope t, NodeScope c, String e, NodeScope f) { 
            err = e; _try = t; _catch = c; _finally = f; 
        }
    }
    
    abstract public String toString();
    abstract void host(Visitor visitor);
    interface Visitor {
        void visit(Assign assignment);
        void visit(Declare declaration);
        void visit(Expr expression);
        void visit(If statement);
        void visit(While loop);
        void visit(For loop);
        void visit(ForEach loop);
        void visit(Scope scope);
        void visit(Break statement);
        void visit(Continue statement);
        void visit(Return statement);
        void visit(Function definition);
        void visit(TryCatch block);
    }
}

class NodeParam {
    final String param; final NodeExpr _default;
    NodeParam(String p, NodeExpr e) { param = p; _default = e; }
    public String toString() { return param + (_default == null ? "" : (" = " + _default)); }
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
    public final int line;
    private NodeExpr(int ln) { line = ln;}

    public static final NodeExpr NULL = new NodeExpr.Term(NodeTerm.NULL, 0);
    static class Binary extends NodeExpr {
        final NodeTerm lhs, rhs; final BinaryOp op;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s %s %s", lhs, op, rhs); }
        Binary(BinaryOp o, NodeTerm l, NodeTerm r, int ln) { 
            super(ln); op = o; lhs = l; rhs = r; 
        }
    }
    
    static class Term extends NodeExpr {
        final NodeTerm val;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return val.toString(); } 
        Term(NodeTerm v, int ln) { super(ln); val = v; }
    }

    static class Lambda extends NodeExpr {
        final List<NodeParam> params;
        final NodeScope body;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() {
            return String.format("function (%s) %s",
                String.join(", ", 
                    params.stream()
                    .map(p -> p.toString())
                    .collect(Collectors.toList())), 
                body); 
        } 
        Lambda(List<NodeParam> a, NodeScope b, int ln) { 
            super(ln); params = a; body = b; 
        }
    }

    abstract public String toString();
    abstract <R> R host(Visitor v);
    interface Visitor {
        <R> R visit(Binary node);
        <R> R visit(Lambda function);
        <R> R visit(Term node);
    }
}

// MARK: NodeTerm
enum UnaryOp { 
    Increment("++"), Decrement("--"), 
    Negate("-"), Invert("~"), Not("!");

    private final String value;
    private UnaryOp(String v) { value = v; }
    public String toString() {
        return value;
    }
}

interface NodeTerm {
    public static final NodeTerm NULL = new NodeTerm.Literal<Void>(null);
    static class Expr implements NodeTerm {
        final NodeExpr expr;
        public Expr(NodeExpr e) { expr = e; }
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("(%s)", expr); }
    }

    static class ArrayLiteral implements NodeTerm {
        final List<NodeExpr> items;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.valueOf(items); } 
        public ArrayLiteral(List<NodeExpr> i) { items = i; }
    }

    static class MapLiteral implements NodeTerm {
        final List<NodeMapEntry> items;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { 
            if (items.size() == 0) {
                return "{}";
            }
            return "{\n" + String.join(", \n", items.stream().map(e -> e.toString()).collect(Collectors.toList())).indent(2) + "}"; 
        } 
        public MapLiteral(List<NodeMapEntry> i) { items = i; }
    }

    static class UnaryExpr implements NodeTerm {
        final UnaryOp op; final NodeTerm val;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s%s", op, val); }
        UnaryExpr(UnaryOp o, NodeTerm v) { op = o; val = v; }
    }

    static class ArrayAccess implements NodeTerm {
        final NodeTerm array; final NodeExpr index;
        public <R> R host(Visitor v) { return v.visit(this); } 
        public String toString() { return String.format("%s[%s]", array, index); } 
        public ArrayAccess(NodeTerm a, NodeExpr i) { array = a; index = i; }
    }
    
    static class Variable implements NodeTerm {
        public final String var;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return var; } 
        public Variable(String v) { this.var = v; }
    }
    
    static class PropAccess implements NodeTerm {
        final NodeTerm object; final String prop;
        public <R> R host(Visitor visitor) { return visitor.visit(this); }
        public String toString() { return object.toString() + "." + prop; } 
        public PropAccess(NodeTerm o, String p) { object = o; prop = p; }
    }

    static class Literal<T> implements NodeTerm {
        final T lit;
        @SuppressWarnings("unchecked")
        public T host(Visitor v) { return v.visit(this); }
        public String toString() { 
            if (lit instanceof String) {
                return "\"" + lit + "\"";
            }
            else {
                return String.valueOf(lit); 
            }
        }
        public Literal(T l) { lit = l; }
    }
    
    static class Call implements NodeTerm {
        final NodeTerm f; final List<NodeExpr> args;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s(%s)", f, String.join(", ", args.stream()
            .map(a -> a.toString())
            .collect(Collectors.toList()))
        ); } 
        public Call(NodeTerm t, List<NodeExpr> e) { f = t; args = e; }
    }

    static class Cast implements NodeTerm {
        final NodeTerm object; final NodeType type;
        public <R> R host(Visitor v) { return v.visit(this); }
        public String toString() { return String.format("%s as %s", object, type); } 
        public Cast(NodeTerm o, NodeType t) { object = o; type = t; }
    }

    public String toString();
    <R> R host(Visitor term);
    interface Visitor {
        <R> R visit(Expr expr);
        <R> R visit(ArrayLiteral arr);
        <R> R visit(MapLiteral map);
        <R> R visit(UnaryExpr expr);
        <R> R visit(ArrayAccess acc);
        <R> R visit(Variable var);
        <R> R visit(PropAccess var);
        <R> R visit(Literal<?> lit);
        <R> R visit(Call call);
        <R> R visit(Cast cast);
    }
}

class NodeMapEntry {
    final String key; final NodeExpr value;
    NodeMapEntry(String p, NodeExpr e) { key = p; value = e; }
    public String toString() { 
        return key + ": " + String.valueOf(value); 
    }
}

class NodeType {
    final String type;
    public String toString() { return type; }
    NodeType(String t) { type = t; }
}

// class NodeVar {
//     final String name;
//     public String toString() { return this.name; }
//     NodeVar(String name) { this.name = name; }
// }
