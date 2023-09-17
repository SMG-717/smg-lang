package interpreter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class Parser {
    
    private final List<Token> cache;
    private final Tokeniser tokeniser;
    private NodeExpression root = null;

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
    public NodeExpression parse() {
        tokeniser.reset();
        root = parseBexpr();

        if (peek() != Token.EOT) {
            throw new RuntimeException("Unexpected token at End of Expression: " + peek().value);
        }
        return root;
    }

    /**
     * Parse Boolean Expression
     * 
     * Attempts to cover the entire expression and calls other parser functions when necessary. It decides how to generate
     * the tree based on the first few tokens, and whether or not other parsers can fully process some tokens.
     * 
     * In language grammer terms, the production of a boolean expression is as follows:
     * 
     * BExpr -> [Comp] | [BExpr] and [BExpr] | [BExpr] or [BExpr] | ([BExpr]) | not ([BExpr]) 
     * 
     * One important aspect is the differentiation of parenthesis based how they appear in the stream of tokens. In this
     * context they shall be called "boolean" and "term" brackets, for brackets that exist on the boolean expression, and
     * term expression levels respectively. Boolean brackets are distinguishingly followed by a boolean expression token
     * (And, Or), while term brackets do not. There is obviously some overlap in this criteria, as the closing both
     * brackets can be followed by an EOT (End of Tokens) Token for example. In this case, and other similar cases, the
     * tie goes to boolean brackets, as the problem arises when term brackets are interpreted as boolean brackets, but 
     * not the other way around. 
     * 
     * Not operators can also make an appearance but not without an associated set of brackets, in which case they are 
     * treated in a similar vein to boolean brackets.
     */
    private NodeExpression parseBexpr() {
        final NodeExpression temp;
        if (peek() == Token.OpenParen || peek() == Token.Not) {

            // Find the matching closing parenthesis
            int ahead = 1, balance = 0;
            boolean termParen = false;
            while (peek(ahead) != Token.EOT) {
                if (peek(ahead) == Token.OpenParen) balance += 1;
                else if (peek(ahead) == Token.CloseParen) balance -= 1;

                ahead += 1;
                if (balance < 0) {
                    // If the closing parenthesis seems to belong to a Comparison node, it is treated as a term
                    termParen = !(peek(ahead).type == TokenType.BooleanArithmetic || peek(ahead) == Token.EOT);
                    break;
                }
            }
            
            if (termParen) temp = new NodeExpression.Comp(parseComp());

            // Otherwise, assume the parentheses are boolean
            else {
                if (tryConsume(Token.OpenParen)) 
                    temp = parseBexpr();
                else if (tryConsume(Token.Not) && tryConsume(Token.OpenParen)) 
                    temp = new NodeExpression.Not(parseBexpr()); 
                else 
                    throw new RuntimeException("Unexpected Token: " + peek().value);
                    
                if (!tryConsume(Token.CloseParen))
                    throw new RuntimeException("Expected ')'");

            }
        }
        
        else temp = null;

        // First part of the expression should be a comp if it doesn't start with boolean parentheses or a not.
        final NodeExpression bexpr;
        final NodeComp comp = parseComp();
        
        bexpr = temp == null? comp == null ? null : new NodeExpression.Comp(comp) : temp;
        if (bexpr != null) {
            if (peek() == Token.EOT || peek() == Token.CloseParen) {
                return bexpr;
            }
            else if (peek().type == TokenType.BooleanArithmetic) {
                return booleanNode(consume(), bexpr, parseBexpr());
            }
            else { 
                throw new RuntimeException("Unexpected token: " + peek().value + " (" + peek().type + ")");
            }
        } 

        else {
            throw new RuntimeException("Something went wrong!");
        }
    }

    /*
     * Helper function that assigns the correct boolean node
     */
    private NodeExpression booleanNode(Token op, NodeExpression lhs, NodeExpression rhs) {
        if (op == Token.And) return new NodeExpression.And(lhs, rhs);
        else if (op == Token.Or) return new NodeExpression.Or(lhs, rhs);
        else {
            throw new RuntimeException("Unsupported boolean operation: " + op.value);
        }
    }

    /*
     * Parse Comparison
     * 
     * A comparison can either be a calculation that compares two double-generating term and returns a boolean answer,
     * or a simple atomic term that can evaluate to anything. Note that the interpreter requires that comps return a 
     * boolean but the parser does not care about that.
     * 
     * The production for a Comp in grammer form looks like the following:
     * Comp -> [Term] | [Term] = [Term] | [Term] != [Term] | [Term] > [Term] | [Term] >= [Term] | [Term] < [Term] | [Term] <= [Term]
     * 
     * Comps must either be simple Terms or two Terms compared by an Equality operator 
     */
    private NodeComp parseComp() {

        final NodeTerm term = parseTerm();        
        if (peek().type != TokenType.Equality) return new NodeComp.Term(term);

        final Token op = consume();
        final NodeTerm other = parseTerm();

        if (op == Token.Equals)             return new NodeComp.Binary(NodeComp.Operator.Equal, term, other);
        else if (op == Token.NotEquals)     return new NodeComp.Binary(NodeComp.Operator.NotEqual, term, other);
        else if (op == Token.Greater)       return new NodeComp.Binary(NodeComp.Operator.GreaterThan, term, other);
        else if (op == Token.GreaterEqual)  return new NodeComp.Binary(NodeComp.Operator.GreaterThanEqual, term, other);
        else if (op == Token.Less)          return new NodeComp.Binary(NodeComp.Operator.LessThan, term, other);
        else if (op == Token.LessEqual)     return new NodeComp.Binary(NodeComp.Operator.LessThanEqual, term, other);
        else {
            throw new RuntimeException("Unsupported Equality operator: " + op.value);
        }
    }

    /*
     * Parse Term
     * 
     * Terms are expressions that can be evaluated and used in comparisions or as boolean expressions on their own.
     * A Term can be composed of an Atomic expression, or a sequence of Atomic expressions separated by arithmetic operators.
     * 
     * The production for Terms is as follows:
     * 
     * Term -> [Atom] | 
     * prec 1:  [Term] + [Term] | [Term] - [Term] |  
     * prec 2:  [Term] * [Term] | [Term] / [Term] | [Term] % [Term] |  
     * prec 3:  [Term] ^ [Term]  
     * 
     * Note the addition of "prec" in the grammar production, which stands for precedence. Operators with higher precedence
     * get deeper nodes in the resulting final tree, which leads to getting calculated first in the interpreter. Term parser
     * will run through all the tokens it can while maintaining a certain precedence level. If a higher precedence operator
     * appears, it would invokde a deeper parseTerm process with a precedence level equivalent to the operator it encountered.
     * If a lower precedence operator is encountered the process stops and returns the tree it built along the way. This
     * way the lower precedence process would pick up from where it left off. The lowest precedence level is 0, which is 
     * the default.
     * 
     * This function could maybe do with some improvements.
     */

    private NodeTerm parseTerm() { return parseTerm(null, 0); }
    private NodeTerm parseTerm(NodeTerm term, int prec) {

        term = term == null ? parseAtom() : term;
        while (peek().type == TokenType.Arithmetic) {
            if (peek().precedence > prec) {
                term = parseTerm(term, peek().precedence);
                continue;
            } 

            final Token op = consume();
            NodeTerm other = parseAtom();
            if (other == null) {
                throw new RuntimeException("Expected term");
            } else if (peek().type != TokenType.Arithmetic || peek().precedence < prec) {
                return arthmeticNode(op, term, other);
            } else while (peek().type == TokenType.Arithmetic && peek().precedence > prec) {
                other = parseTerm(other, peek().precedence);
            }
            term = arthmeticNode(op, term, other);
        }

        return term;
    }

    /*
     * Helper function that assigns the correct arithmetic node
     */
    private NodeTerm arthmeticNode(Token op, NodeTerm lhs, NodeTerm rhs) {
        if (op == Token.Plus) return new NodeTerm.Arithmetic(NodeTerm.Arithmetic.Operator.Add, lhs, rhs);
        else if (op == Token.Minus) return new NodeTerm.Arithmetic(NodeTerm.Arithmetic.Operator.Subtract, lhs, rhs);
        else if (op == Token.Star) return new NodeTerm.Arithmetic(NodeTerm.Arithmetic.Operator.Multiply, lhs, rhs);
        else if (op == Token.Div) return new NodeTerm.Arithmetic(NodeTerm.Arithmetic.Operator.Divide, lhs, rhs);
        else if (op == Token.Mod) return new NodeTerm.Arithmetic(NodeTerm.Arithmetic.Operator.Modulus, lhs, rhs);
        else if (op == Token.Power) return new NodeTerm.Arithmetic(NodeTerm.Arithmetic.Operator.Exponent, lhs, rhs);
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

    private NodeTerm parseAtom() {
        final NodeTerm term;
        final NodeLiteral<?> lit;
        final NodeVariable var;
        if (tryConsume(Token.OpenParen)) {
            term = parseTerm();
            if (!tryConsume(Token.CloseParen)) {
                throw new RuntimeException("Expected ')'");
            }
            return term;
        } 
        else if ((var = parseVariable()) != null) {
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
        if (peek().type != TokenType.Qualifier) {
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
            return new NodeEmpty();
        }

        switch (peek().type) {
            case BooleanLiteral:
                return new NodeBoolean(consume().equals(Token.True));
            case StringLiteral:
                return new NodeString(consume().value);
            case NumberLiteral:
                return new NodeNumber(Double.parseDouble(consume().value));
            case DateLiteral:
                final String dateToken = consume().value;
                try {
                    return new NodeDate(format.parse(dateToken));
                } catch (ParseException e) {
                    throw new RuntimeException("Date format error: " + dateToken);
                }
            default:
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

    public NodeExpression getRoot() {
        return root;
    }
}

class NodeProgram {
    public final List<NodeStatement> statements;

    NodeProgram(List<NodeStatement> statements) {
        this.statements = List.copyOf(statements);
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
    }    
}

interface NodeExpression extends NodeStatement {
    class And implements NodeExpression {
        public final NodeExpression lhs, rhs;
    
        And(NodeExpression lhs, NodeExpression rhs) {
            this.lhs = lhs;
            this.rhs = rhs;
        }

        public boolean host(Visitor visitor) {
            return visitor.visit(this);
        }
    }

    class Or implements NodeExpression{
        public final NodeExpression lhs, rhs;
    
        Or(NodeExpression lhs, NodeExpression rhs) {
            this.lhs = lhs;
            this.rhs = rhs;
        }

        public boolean host(Visitor visitor) {
            return visitor.visit(this);
        }
    }
    
    class Not implements NodeExpression {
        public final NodeExpression val;
    
        Not(NodeExpression val) {
            this.val = val;
        }

        public boolean host(Visitor visitor) {
            return visitor.visit(this);
        }
    }
    
    class Comp implements NodeExpression {
        public final NodeComp val;
    
        Comp(NodeComp val) {
            this.val = val;
        }

        public boolean host(Visitor visitor) {
            return visitor.visit(this);
        }
    }

    boolean host(Visitor visitor);
    interface Visitor {
        boolean visit(And node);
        boolean visit(Or node);
        boolean visit(Not node);
        boolean visit(Comp node);
    }
}

interface NodeComp {
    class Binary implements NodeComp {
        public final NodeTerm lhs;
        public final NodeTerm rhs;
        public final Operator op;
    
        Binary(Operator op, NodeTerm lhs, NodeTerm rhs) {
            this.op = op;
            this.lhs = lhs;
            this.rhs = rhs;
        }

        public boolean host(Visitor visitor) {
            return visitor.visit(this);
        }
    }
    
    class Term implements NodeComp {
        public final NodeTerm val;
        public Term(NodeTerm val) {
            this.val = val;
        }
        
        public boolean host(Visitor visitor) {
            return visitor.visit(this);
        }
    }

    enum Operator {
        GreaterThan, GreaterThanEqual, LessThan, LessThanEqual, Equal, NotEqual
    }

    boolean host(Visitor visitor);
    interface Visitor {
        boolean visit(Binary node);
        boolean visit(Term node);
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
    }
    
    class Arithmetic implements NodeTerm {
        
        public final NodeTerm lhs;
        public final NodeTerm rhs;
        public final Operator op;
    
        Arithmetic(Operator op, NodeTerm lhs, NodeTerm rhs) {
            this.op = op;
            this.lhs = lhs;
            this.rhs = rhs;
        }

        enum Operator {
            Exponent,
            Multiply, Divide, Modulus,
            Add, Subtract,
        }

        @Override
        public Object host(Visitor visitor) {
            return visitor.visit(this);
        }
    }

    Object host(Visitor term);
    interface Visitor {
        Object visit(Literal lit);
        Object visit(Variable var);
        Object visit(Arithmetic arithmetic);
    }
}

class NodeVariable {
    public final String name;
    NodeVariable(String name) {
        this.name = name;
    }
}

abstract class NodeLiteral<R> {
    public final R val;

    NodeLiteral(R val) {
        this.val = val;
    }
}

class NodeNumber extends NodeLiteral<Double> {
    public NodeNumber(Double val) {
        super(val.doubleValue());
    }
}

class NodeDate extends NodeLiteral<Date> {
    public NodeDate(Date val) {
        super(val);
    }
}

class NodeString extends NodeLiteral<String> {
    public NodeString(String val) {
        super(val);
    }
}

class NodeBoolean extends NodeLiteral<Boolean> {
    public NodeBoolean(Boolean val) {
        super(val.booleanValue());
    }
}

class NodeEmpty extends NodeLiteral<Void> {
    public NodeEmpty() {
        super(null);
    }
}
