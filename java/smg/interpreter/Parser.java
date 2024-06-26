package smg.interpreter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static NodeProgram parseFile(Path path) throws IOException {
        return parse(String.join("\n", Files.readAllLines(path)));
    }

    public static NodeProgram parse(String code) {
        return new Parser(code).parse();
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
        final NodeProgram program = new NodeProgram(new LinkedList<>());
        
        do {
            // [StmtTerm]*
            while (tryConsume(TokenType.StatementTerminator) != null);
            
            // [Statement]?
            NodeStmt stmt;
            if ((stmt = parseStatement()) == null) break;
            program.stmts.add(stmt);
        } 
        while (peek() != Token.EOT);
        return program;
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
            if (peek() == Token.If) 
                scopeElse = new NodeScope(List.of(parseIf()));
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
    // ForLoop -> 'for' '(' ([Assign] | [Decl])? ';' [Expr]? ';' 
    //     ([Assign] | [Expr])? ')' [Scope]
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
            
            final NodeStmt.Declare init = peek() == Token.SemiColon ? null : 
            tryParse(parseDecl(), "Expected declaration.");
            tryConsume(Token.SemiColon, "Expected ';'");
            
            final NodeExpr cond = peek() == Token.SemiColon ? null : 
            tryParse(parseExpr(), "Expected boolean expression");
            tryConsume(Token.SemiColon, "Expected ';'");
            
            final NodeStmt inc;
            if (peek() != Token.CloseParen) {
                NodeStmt stmt; NodeTerm term;
                if ((stmt = parseAssign(term = parseTerm())) != null);
                else if ((stmt = parseStmtExpr(term)) != null);
                else throw error("Expected an increment assignment or expr");

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
        else if (tryConsume(Token.Return)) 
            return new NodeStmt.Return(parseExpr());

        return null;
    }

    // Func -> 'define' [Variable] [ParamList] [Scope]
    private NodeStmt.Function parseFunction() {
        // If it has a qualifier, it is a proper function and not a lambda
        if (peek() != Token.Function || 
            !peekNonBlank(1).isAny(TokenType.Qualifier)) 
            return null;

        tryConsume(Token.Function);
        return new NodeStmt.Function(parseVariable(), parseParams(), tryParse(
            parseScope(), 
            "Expected function body"
        ));
    }

    // ParamList -> '(' ([Param] (',' [Param])*)? ')'
    private List<NodeParam> parseParams() {
        final List<NodeParam> params = new LinkedList<>();
        tryConsume(Token.OpenParen, "Expected '('");
        if (peek() != Token.CloseParen) {
            do {
                params.add(tryParse(
                    parseParam(), 
                    "Unexpected Token: " + peek().value
                ));
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
            final boolean paren = tryConsume(Token.OpenParen);
            err = parseVariable();

            if (paren) tryConsume(Token.CloseParen, "Expected ')'");
            catchScope = tryParse(parseScope(), "Expected catch block.");
        }
        else {
            catchScope = null; err = null;
        }

        finallyScope = !tryConsume(Token.Finally) ? null :
            tryParse(parseScope(), "Expected finally block.");
            
        return new NodeStmt.TryCatch(tryScope, catchScope, err, finallyScope);
    }

    /** It is the responsibility of callers to parse the LHS for assignments */
    private NodeStmt.Assign parseAssign(NodeTerm term) {
        
        final boolean assignType = term instanceof NodeTerm.ArrayAccess || 
            term instanceof NodeTerm.PropAccess || 
            term instanceof NodeTerm.Variable;

        if (!(assignType && peek().isAny(TokenType.AssignOperator))) 
            return null;
        
        final Token op = tryConsume(TokenType.AssignOperator);
        final NodeExpr expr = tryParse(parseExpr(), "Expected expression");
        return arthmeticAssignNode(op, term, expr);
    }

    private NodeStmt.Expr parseStmtExpr(NodeTerm term) {
        return term == null ? null : new NodeStmt.Expr(parseExpr(term, 0));
    }

    private NodeStmt.Assign arthmeticAssignNode(
        Token op, NodeTerm lhs, NodeExpr rhs
    ) {
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
        final NodeTerm term;
        if (peek().isAny(TokenType.FunctionDecl)) return parseLambda();
        else if ((term = parseTerm()) != null) return parseExpr(term, 0);
        else return null;
    }

    private NodeExpr.Lambda parseLambda() {
        final int currentline = line;
        if (tryConsume(TokenType.FunctionDecl) == null) return null;
        tryConsume(Token.OpenParen, "Expected '('");
        final List<NodeParam> params = new LinkedList<>();
        if (peek() != Token.CloseParen) {
            do {
                params.add(tryParse(
                    parseParam(), 
                    "Unexpected Token: " + peek().value
                ));
            } 
            while (tryConsume(Token.Comma));
        }
        tryConsume(Token.CloseParen, "Expected ')'");

        final NodeScope lambody;
        if (peek() == Token.OpenCurly) {
            lambody = tryParse(
                parseScope(),
                "Expected function body or expression"
            );
        }
        else {
            lambody = new NodeScope(List.of(
                new NodeStmt.Return(tryParse(
                    parseExpr(), 
                    "Expected function body or expression"
                ))
            ));
        }

        return new NodeExpr.Lambda(params, lambody, currentline);
    }
    
    private NodeExpr parseExpr(NodeTerm left, int prec) {
        final int cline = line;
        while (peek().isAny(TokenType.BinaryArithmetic) && peek().prec >= prec) 
        {
            final Token op = tryConsume(TokenType.BinaryArithmetic);
            NodeTerm right = parseTerm();

            while (peek().isAny(TokenType.BinaryArithmetic) && (
                (peek().prec > op.prec) || 
                (peek().prec >= op.prec && peek().rassoc))) {
                right = new NodeTerm.Expr(
                    parseExpr(right, op.prec + (peek().prec > op.prec ? 1 : 0))
                );
            }
            left = new NodeTerm.Expr(arthmeticNode(op, left, right));
        }

        return left instanceof NodeTerm.Expr ? ((NodeTerm.Expr) left).expr : 
            new NodeExpr.Term(left, cline);
    }

    private NodeExpr.Binary arthmeticNode(Token op, NodeTerm lhs, NodeTerm rhs)
    {
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
        
        // Primary term parsed. It could be followed by any number of other 
        // items to modify it.
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
        final var arr = new NodeTerm.ArrayLiteral(
            new LinkedList<>()
        );
        
        if (tryConsume(Token.CloseSquare)) return arr;
        NodeExpr expr;

        do {
            if ((expr = parseExpr()) == null) break;
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

        final var arr = new NodeTerm.MapLiteral(new LinkedList<>());
        if (tryConsume(Token.CloseCurly)) return arr;
        
        do {
            final String key = parseVariable();
            if (key == null) break;
            
            tryConsume(Token.Colon, "Expected ':");
            arr.items.add(new NodeMapEntry(key, tryParse(
                parseExpr(), "Expected expression"
            )));
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

        return unaryArthmeticNode(uop, tryParse(
            parseTerm(), "Expected atomic expression"
        ));
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

        final NodeExpr expr = tryParse(
            parseExpr(), "Expected array index expression."
        );

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
            call.args.add(tryParse(parseExpr(), "Expected expression, found: " + peek().value));
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
        final Token token = tryConsume(TokenType.Qualifier);
        return token == null ? null : token.value;
    }
    
    // Term.Literal<?>
    private NodeTerm.Literal<?> parseLiteral() {
        Token token;
        if (tryConsume(Token.Null)) 
            return new NodeTerm.Literal<Void>(null);
        else if ((token = tryConsume(TokenType.BooleanLiteral)) != null) 
            return new NodeTerm.Literal<Boolean>(token.equals(Token.True));
        else if ((token = tryConsume(TokenType.StringLiteral)) != null)
            return new NodeTerm.Literal<String>(token.value);
        else if ((token = tryConsume(TokenType.NumberLiteral)) != null) {
            final String repr = token.value;
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
        final Token consumable = cache.size() > 0 ? 
            cache.remove(0) : 
            tokeniser.nextToken();

        if (!consumable.isAny(TokenType.StringLiteral))
            line += consumable.value.chars().filter(i -> i == '\n').count(); 
        
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
        while (peek() == Token.Newline || peek().isAny(TokenType.Comment)) 
            consume();
    }

    private Token peekNonBlank() {
        return peekNonBlank(1);
    }

    private Token peekNonBlank(int count) {
        int ahead = 1;
        while (count > 0) {
            while (
                peek(ahead) == Token.Newline || 
                peek(ahead).isAny(TokenType.Comment)
            ) ahead += 1;
           
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

    public static String indent(String text, int count) {
        final String spaces = " ".repeat(count);
        return String.join("\n", text.lines()
            .map(l -> spaces + l)
            .collect(Collectors.toList())
        );
    }
}