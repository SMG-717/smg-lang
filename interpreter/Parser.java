package interpreter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class Parser {
    
    private final List<Token> cache;
    private final Tokeniser tokeniser;
    private NodeProgram root = null;

    public Parser(Tokeniser tokeniser) {
        this.tokeniser = tokeniser;
        this.cache = new LinkedList<>();
    }
    
    public Parser(String input) {
        // The input expression should always end in an End of File token (\0 character)
        tokeniser = new Tokeniser(input + Token.EOF);
        this.cache = new LinkedList<>();
    }

    /***************************************************************************
     * Parser
     * 
     * Runs through every token in an expression and builds an Abstract Syntax
     * Tree. The top level node does not necessarily have to be a Boolean 
     * Expression node. The trees it generates are as compact as they can be,
     * which might make it harder for the interpreter to traverse, but is more
     * space and time efficient.
     **************************************************************************/
    public NodeProgram parse() {
        tokeniser.reset();
        root = parseProgram();

        if (peek() != Token.EOT) {
            throw new RuntimeException("Unexpected token at End of Expression: " + peek().value);
        }
        return root;
    }

    private NodeProgram parseProgram() {

        List<NodeStatement> statements = new ArrayList<>();
        while (peek() != Token.EOT) {
            NodeStatement statement = parseStatement();
            if (statement == null) {
                throw new RuntimeException("Incomplete/Unparsable statement");
            }
            statements.add(statement);
        }

        return new NodeProgram(statements);
    }

    private NodeStatement parseStatement() {

        if (tryConsume(Token.Let)) {
            throw new RuntimeException("Unsupported operation: let");
        }

        final NodeExpression bexpr = parseExpression();
        return bexpr == null ? null : new NodeStatement.Expression(bexpr);
    }

    private NodeExpression parseExpression() { return parseExpression(null, 0); }
    private NodeExpression parseExpression(NodeExpression term, int prec) {

        if (tryConsume(Token.OpenParen)) {
            term = parseExpression(term, 0);
            if (!tryConsume(Token.CloseParen)) {
                throw new RuntimeException("Expected ')'");
            }
        } 

        if (peek().is(TokenType.UnaryArithmetic)) {
            final Token op = consume();

            final NodeExpression innerTerm; 
            if (tryConsume(Token.OpenParen)) {
                innerTerm = parseExpression(term, 0);
                if (!tryConsume(Token.CloseParen)) {
                    throw new RuntimeException("Expected ')'");
                }
            } 
            else {
                innerTerm = new NodeExpression.Term(parseTerm());
            }

            if (op == Token.Not) {
                return new NodeExpression.Unary(innerTerm, NodeExpression.Unary.Operator.Not);
            }
            else if (op == Token.Tilde) {
                return new NodeExpression.Unary(innerTerm, NodeExpression.Unary.Operator.Invert);
            }
            else if (op == Token.Hyphen) {
                return new NodeExpression.Unary(innerTerm, NodeExpression.Unary.Operator.Negate);
            }
            else {
                throw new RuntimeException("Unsupported unary operation: " + op);
            }
        }

        if (term == null) {
            NodeTerm atom = parseTerm();
            term = new NodeExpression.Term(atom);
        }
        
        while (peek().is(TokenType.BinaryArithmetic)) {
            if (peek().precedence > prec) {
                term = parseExpression(term, peek().precedence);
                continue;
            } 

            final Token op = consume();
            final NodeTerm otherAtom = parseTerm();
            if (otherAtom == null) {
                throw new RuntimeException("Expected term");
            }

            NodeExpression other = new NodeExpression.Term(otherAtom);
            if (peek().is(TokenType.BinaryArithmetic) || peek().precedence < prec) {
                return arthmeticNode(op, term, other);
            } else while (peek().is(TokenType.BinaryArithmetic) && peek().precedence > prec) {
                other = parseExpression(other, peek().precedence);
            }
            term = arthmeticNode(op, term, other);
        }

        return term;
    }

    /*
     * Helper function that assigns the correct arithmetic node
     */
    private NodeExpression arthmeticNode(Token op, NodeExpression lhs, NodeExpression rhs) {
        if (op == Token.Plus) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Add, lhs, rhs);
        else if (op == Token.Hyphen) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Subtract, lhs, rhs);
        else if (op == Token.Asterisk) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Multiply, lhs, rhs);
        else if (op == Token.ForwardSlash) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Divide, lhs, rhs);
        else if (op == Token.Percent) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Modulo, lhs, rhs);
        else if (op == Token.Caret) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Exponent, lhs, rhs);
        else if (op == Token.Greater) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Greater, lhs, rhs);
        else if (op == Token.GreaterEqual) return new NodeExpression.Binary(NodeExpression.Binary.Operator.GreaterEqual, lhs, rhs);
        else if (op == Token.Less) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Less, lhs, rhs);
        else if (op == Token.LessEqual) return new NodeExpression.Binary(NodeExpression.Binary.Operator.LessEqual, lhs, rhs);
        else if (op == Token.Equals) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Equal, lhs, rhs);
        else if (op == Token.NotEquals) return new NodeExpression.Binary(NodeExpression.Binary.Operator.NotEqual, lhs, rhs);
        else if (op == Token.And) return new NodeExpression.Binary(NodeExpression.Binary.Operator.And, lhs, rhs);
        else if (op == Token.Or) return new NodeExpression.Binary(NodeExpression.Binary.Operator.Or, lhs, rhs);
        else {
            throw new RuntimeException("Unsupported arithmetic operation: " + peek().value);
        }
    }

    /*
     * Parse atom
     * 
     * Atomic expressions can be either Variables (such as X), Literals (such as 10), or smaller Terms surrounded by 
     * parentheses. This gives parentheses the highest precedence among operations.
     * 
     * The production for atom is as follows:
     * 
     * Atom -> ([Term]) | [Variable] | [Literal]
     */

    private NodeTerm parseTerm() {
        final NodeLiteral<?> lit;
        final NodeVariable var;
        if ((var = parseVariable()) != null) {
            return new NodeTerm.Variable(var);
        } 
        else if ((lit = parseLiteral()) != null) {
            return new NodeTerm.Literal(lit);
        } else {
            return null;
        }
    }

    /*
     * Parse Variable
     * 
     * Variable names, here referred to as Qualifiers, can consist of any combination of alphanumeric characters or 
     * underscores but not start with a number. Variables refer to values that are provided later at run time under 
     * specific names. Variables can consist of two Qualifiers separated by a period.
     * 
     * Variable production is as follows:
     * 
     * Variable -> [Qualifier] | [Qualifier].[Qualifier] 
     */
    private NodeVariable parseVariable() {
        if (!peek().is(TokenType.Qualifier)) {
            return null;   
        }

        return new NodeVariable(consume().value);
    }

    /*
     * Parse Literal
     * 
     * Any free form values not bound to variables or the results of computation are called Literals.
     * Literals can be boolean such as true or false, numeric such as 10 or 17.07, strings such as "SMG" or 'Kyle' and 
     * dates such as 31/12/2023 (must be in the format dd/MM/yyy), and finally the empty keyword, which represents null
     * or 0. 
     */
    private static final SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy"); 
    private NodeLiteral<?> parseLiteral() {

        if (tryConsume(Token.Empty)) {
            return new NodeLiteral<Void>(null);
        }

        if (peek().is(TokenType.BooleanLiteral)) 
            return new NodeLiteral<Boolean>(consume().equals(Token.True));
        else if (peek().is(TokenType.StringLiteral))
            return new NodeLiteral<String>(consume().value);
        else if (peek().is(TokenType.NumberLiteral))
            return new NodeLiteral<Double>(Double.parseDouble(consume().value));
        else if (peek().is(TokenType.DateLiteral)) {
            final String dateToken = consume().value;
            try {
                return new NodeLiteral<Date>(format.parse(dateToken));
            } catch (ParseException e) {
                throw new RuntimeException("Date format error: " + dateToken);
            }
        }
        else {
            return null;
        }
    }

    /***************************************************************************
     * Token Handling
     * 
     * The Parser periodically requests tokens from the Tokeniser as and when it 
     * needs them. This saves space and is at the minimum as efficient as generating 
     * all tokens first. It also helps in the case of an error, where all the tokens
     * after an incorrect syntax need not be generated and overall reduce compute time.
     * 
     * The Tokeniser keeps a cache of tokens for use and although it usually by 
     * default keeps one token in cache, it can generate more tokens on demand. 
     * These extra tokens would not need to be generated again on future 
     * invocations of peek() or consume(). 
     **************************************************************************/
    private Token peek() {
        return peek(0);
    }
    
    private Token consume() {
        return cache.size() > 0 ? cache.remove(0) : tokeniser.nextToken();
    }

    private Token peek(int offset) {
        while (cache.size() <= offset) {
            cache.add(tokeniser.nextToken());
        }
        return cache.get(offset);
    }

    private boolean tryConsume(Token token) {
        final boolean success;
        if ((success = peek() == token))
            consume();
        return success;
    }

    public NodeProgram getRoot() {
        return root;
    }
}

class NodeProgram {
    public final List<NodeStatement> statements;

    NodeProgram(List<NodeStatement> statements) {
        this.statements = List.copyOf(statements);
    }

    public String toString() {
        return "Program: { " + String.join(", ", statements.stream().map(x -> x.toString()).collect(Collectors.toList())) + " }";
    }
}

interface NodeStatement {
    class Assignment implements NodeStatement {
    
        public final NodeVariable qualifier;
        public final NodeExpression expression;
    
        Assignment(NodeVariable qualifier, NodeExpression expression) {
            this.qualifier = qualifier;
            this.expression = expression;
        }
        
        public Object host(Visitor visitor) { return visitor.visit(this); }

        
        public String toString() {
            return "StmtAssign: { " + qualifier + "=" + expression + " }";
        } 
    }

    class Expression implements NodeStatement {
        public final NodeExpression expression;

        Expression(NodeExpression expression) {
            this.expression = expression;
        }
        
        public Object host(Visitor visitor) { return visitor.visit(this); }

        public String toString() {
            return "StmtExp: { " + expression + " }";
        } 
    }

    Object host(Visitor visitor);
    interface Visitor {
        Object visit(Assignment assignment);
        Object visit(Expression expression);
    }
}

interface NodeExpression {
    class Binary implements NodeExpression {
        public final NodeExpression lhs, rhs;
        public final Operator op;
    
        Binary(Operator op, NodeExpression lhs, NodeExpression rhs) {
            this.op = op;
            this.lhs = lhs;
            this.rhs = rhs;
        }

        public <R> R host(Visitor<R> visitor) { return visitor.visit(this); }

        enum Operator {                                 // Precedence
            Exponent,                                       // 8
            Multiply, Divide, Modulo,                       // 7
            Add, Subtract,                                  // 6
            ShiftLeft, ShiftRight,                          // 5
            Less, LessEqual, Greater, GreaterEqual,         // 4
            Equal, NotEqual,                                // 3
            BitAnd, BitOr, BitXor,                          // 2
            And, Or                                         // 1
        }

        public String toString() {
            return "ExprBin: { " + lhs + " " + op + " " + rhs + " }";
        } 
    }
    
    class Unary implements NodeExpression {
        public final NodeExpression val;
        public final Operator op;
    
        Unary(NodeExpression val, Operator op) {
            this.op = op;
            this.val = val;
        }

        public <R> R host(Visitor<R> visitor) {
            return visitor.visit(this);
        }

        enum Operator {
            Increment, Decrement,
            Negate, Invert, Not
        }
        
        public String toString() {
            return "ExprUn: { " + op + " " + val + " }";
        } 
    }
    
    class Term implements NodeExpression {
        public final NodeTerm val;
    
        Term(NodeTerm val) {
            this.val = val;
        }

        public <R> R host(Visitor<R> visitor) {
            return visitor.visit(this);
        }
        
        public String toString() {
            return "ExprTerm: { " + val + " }";
        } 
    }

    <R> R host(Visitor<R> visitor);
    interface Visitor<R> {
        R visit(Binary node);
        R visit(Unary node);
        R visit(Term node);
    }
}

interface NodeTerm {
    class Literal implements NodeTerm {
        public final NodeLiteral<?> lit;
        public Literal(NodeLiteral<?> lit) {
            this.lit = lit;
        }
        
        @Override
        public Object host(Visitor visitor) {
            return visitor.visit(this);
        }

        public String toString() {
            return "TermLit: { " + lit + " }";
        } 
    }
    
    class Variable implements NodeTerm {
        public final NodeVariable var;
        public Variable(NodeVariable var) {
            this.var = var;
        }
        
        @Override
        public Object host(Visitor visitor) {
            return visitor.visit(this);
        }
        
        public String toString() {
            return "TermVar: { " + var + " }";
        } 
    }

    Object host(Visitor term);
    interface Visitor {
        Object visit(Literal lit);
        Object visit(Variable var);
    }
}

class NodeVariable {
    public final String name;
    NodeVariable(String name) {
        this.name = name;
    }

    public String toString() {
        return "Var: " + this.name;
    }
}

class NodeLiteral<R> {
    public final R val;

    NodeLiteral(R val) {
        this.val = val;
    }

    public String toString() {
        return "Lit: " + this.val;
    }
}
