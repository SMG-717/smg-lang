package interpreter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Parser {

    /**
     * Parser for a small custom language.
     * 
     * The process is split into three interleaving parts, namely:
     *   - Tokeniser
     *   - Parser
     *   - Interpreter
     * 
     * Although the Tokeniser is independent from the Parser, it works with the
     * latter in tandem to minimise space and resources used.
     */


    private final List<Token> cache;
    private final String expression;
    private final Map<String, Object> variables;
    private boolean answer = false;
    private NodeBExpr root = null;
    private int charIndex = 0;

    public Parser(String input) {
        // The input expression should always end in an End of File token (\0 character)
        this.expression = input + Token.EOF;
        this.cache = new LinkedList<>();
        this.variables = new HashMap<>();
    }

    public Parser(String input, Map<String, Object> variables) {
        this.expression = input + Token.EOF;
        this.cache = new LinkedList<>();
        this.variables = variables;
    }

    /**
     * Variable management.
     * 
     * Crucial for the execution of the interpreter when variables are involved.
     */
    public Parser addVariable(String key, Object value) {
        variables.put(key, value);
        return this;
    }

    public Object getVariable(String key) {
        if (!variables.containsKey(key)) {
            throw new RuntimeException("Variable " + key + " is undefined");
        }
        return variables.get(key);
    }

    public Parser clearVariables() {
        variables.clear();
        return this;
    }

    /***************************************************************************
     * Interpreter
     * 
     * Traverses the Syntax Tree in in-order fashion. Calls the parser if no root
     * node can be found.
     **************************************************************************/
    public boolean interpret() {
        if (root == null)
            parse();
        return this.answer | interpretBexpr(root);
    }

    /**
     * Interpret Boolean Expressrion node.
     * 
     * Since all nodes directly, or indirectly extend BExpr, they get redirected here to their respective interpreters.
     * This interpreter also benefits from Java's inherent evaluation system, where if an expression such as 'true or x'
     * which would normally produce an error if 'x' cannot be evaluated as a boolean would actually evaluate to true.
     * This can be beneficial but potentially hard to debug.
     */
    private boolean interpretBexpr(NodeBExpr node) {
        // Top level boolean arithmetic
        if (node instanceof NodeBExprAnd) {
            return interpretBexpr(((NodeBExprAnd) node).lhs) && interpretBexpr(((NodeBExprAnd) node).rhs);
        } else if (node instanceof NodeBExprOr) {
            return interpretBexpr(((NodeBExprOr) node).lhs) || interpretBexpr(((NodeBExprOr) node).rhs);
        } else if (node instanceof NodeBExprNot) {
            return !interpretBexpr(((NodeBExprNot) node).val);
        } else if (node instanceof NodeBExprComp) {
            return interpretComp(((NodeBExprComp) node).val);
        } else {
            throw new RuntimeException("Unimplemented Boolean Expression Operation");
        }
    }

    /**
     * Interpret Comparision node.
     * 
     * Comparision nodes compare values on both sides of an operator. For a value to be comparable it must first be 
     * represented as a double.
     */
    private boolean interpretComp(NodeComp comp) {

        if (comp instanceof NodeCompTerm) {
            Object value = interpretTerm(((NodeCompTerm) comp).val);
            if (value instanceof Boolean) {
                return (Boolean) value;
            } else {
                throw new RuntimeException("Atomic expression required to be boolean but is not: " + value);
            }
        } else if (!(comp instanceof NodeBinaryComp)) {
            throw new RuntimeException("Unimplemented Comparison Operation");
        }

        double lhs = toDouble(interpretTerm(((NodeBinaryComp) comp).lhs));
        double rhs = toDouble(interpretTerm(((NodeBinaryComp) comp).rhs));

        if (comp instanceof NodeCompEqual) {
            return lhs == rhs;
        } else if (comp instanceof NodeCompNotEqual) {
            return lhs != rhs;
        } else if (comp instanceof NodeCompGreaterThan) {
            return lhs > rhs;
        } else if (comp instanceof NodeCompGreaterThanEqual) {
            return lhs >= rhs;
        } else if (comp instanceof NodeCompLessThan) {
            return lhs < rhs;
        } else if (comp instanceof NodeCompLessThanEqual) {
            return lhs <= rhs;
        } else {
            throw new RuntimeException("Unimplemented Comparison Operation");
        }
    }

    
    /**
     * Interpret Term
     * 
     * Term is an atomic node and is the terminal node in any syntax tree branch. Terms can be literal values or variables
     * that can be provided on Parser creation. Terms can be any value of any type, so long as they fit in higher level 
     * expressions. Qualifier-member nodes will be concatenated with a period "." when grabbed fromn the variable map
     */
    private Object interpretTerm(NodeTerm term) {
        if (term instanceof NodeTermLiteral) {
            return interpretLiteral(((NodeTermLiteral) term).val);
        } else if (term instanceof NodeTermVariable) {
            return interpretVariable(((NodeTermVariable) term).val);
        } else if (term instanceof NodeArithmetic) {
            double lhs = toDouble(interpretTerm(((NodeArithmetic) term).lhs));
            double rhs = toDouble(interpretTerm(((NodeArithmetic) term).rhs));

            if (term instanceof NodeAdd) return lhs + rhs;
            else if (term instanceof NodeSub) return lhs - rhs;
            else if (term instanceof NodeMul) return lhs * rhs;
            else if (term instanceof NodeDiv) return lhs / rhs;
            else if (term instanceof NodeMod) return lhs % rhs;
            else if (term instanceof NodePow) return Math.pow(lhs, rhs);

            return 0;
        } else {
            throw new RuntimeException("Unimplemented Atomic Expression");
        }
    }
    
    private Object interpretLiteral(NodeLiteral term) {
        return ((OpValue<?>) term).val;
    }

    private Object interpretVariable(NodeVariable var) {
        if (var instanceof NodeQualifierMember) {
            NodeQualifierMember qm = (NodeQualifierMember) var;
            return getVariable(qm.lhs + "." + qm.lhs);
        } else if (var instanceof NodeQualifier) {
            NodeQualifier qm = (NodeQualifier) var;
            return getVariable(qm.val);
        } else {
            throw new RuntimeException("Unimplemented Variable Type");
        }
    }
    
    /**
     * To Double.
     * 
     * For a node to viably exist in an equality, or an arithmetic expression, it must have a numerical representation.
     * For this reason, all values are converted and cast into double. Strings are hashed before being evaluated. While
     * it can lead to bizzare results with most inequality operations, it is "good enough" testing if two strings are the 
     * same. All strings that are equal must have the same hash but not all strings with the same has must be equivalent.
     * Read the Java Documentation on Strings for more info.
     */
    private double toDouble(Object value) {
        if (value == null) {
            return 0;
        } else if (value instanceof Date) {
            return ((Date) value).getTime();
        } else if (value instanceof Double) {
            return (double) value;
        } else if (value instanceof Integer) {
            return (double) (int) value;
        } else if (value instanceof String) {
            return (double) ((String) value).hashCode();
        } else {
            throw new RuntimeException("Atomic expression required to be double, or double similar, but is not: " + value);
        }
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
    public NodeBExpr parse() {
        charIndex = 0;
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
    private NodeBExpr parseBexpr() {
        final NodeBExpr temp;
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
            
            if (termParen) temp = new NodeBExprComp(parseComp());

            // Otherwise, assume the parentheses are boolean
            else {
                if (tryConsume(Token.OpenParen)) 
                    temp = parseBexpr();
                else if (tryConsume(Token.Not) && tryConsume(Token.OpenParen)) 
                    temp = new NodeBExprNot(parseBexpr()); 
                else 
                    throw new RuntimeException("Unexpected Token: " + peek().value);
                    
                if (!tryConsume(Token.CloseParen))
                    throw new RuntimeException("Expected ')'");

            }
        }
        
        else temp = null;

        // First part of the expression should be a comp if it doesn't start with boolean parentheses or a not.
        final NodeBExpr bexpr;
        final NodeComp comp = parseComp();
        
        bexpr = temp == null? comp == null ? null : new NodeBExprComp(comp) : temp;
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
    private NodeBExpr booleanNode(Token op, NodeBExpr lhs, NodeBExpr rhs) {
        if (op == Token.And) return new NodeBExprAnd(lhs, rhs);
        else if (op == Token.Or) return new NodeBExprOr(lhs, rhs);
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
        if (peek().type != TokenType.Equality) return new NodeCompTerm(term);

        final Token op = consume();
        final NodeTerm other = parseTerm();

        if (op == Token.Equals)             return new NodeCompEqual(term, other);
        else if (op == Token.NotEquals)     return new NodeCompNotEqual(term, other);
        else if (op == Token.Greater)       return new NodeCompGreaterThan(term, other);
        else if (op == Token.GreaterEqual)  return new NodeCompGreaterThanEqual(term, other);
        else if (op == Token.Less)          return new NodeCompLessThan(term, other);
        else if (op == Token.LessEqual)     return new NodeCompLessThanEqual(term, other);
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
        if (op == Token.Plus) return new NodeAdd(lhs, rhs);
        else if (op == Token.Minus) return new NodeSub(lhs, rhs);
        else if (op == Token.Star) return new NodeMul(lhs, rhs);
        else if (op == Token.Div) return new NodeDiv(lhs, rhs);
        else if (op == Token.Mod) return new NodeMod(lhs, rhs);
        else if (op == Token.Power) return new NodePow(lhs, rhs);
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
        final NodeLiteral lit;
        final NodeVariable var;
        if (tryConsume(Token.OpenParen)) {
            term = parseTerm();
            if (!tryConsume(Token.CloseParen)) {
                throw new RuntimeException("Expected ')'");
            }
            return term;
        } 
        else if ((var = parseVariable()) != null) {
            return new NodeTermVariable(var);
        } 
        else if ((lit = parseLiteral()) != null) {
            return new NodeTermLiteral(lit);
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

        final String qualifier = consume().value;
        if (peek() == Token.Period && peek(1).type == TokenType.Qualifier) {
            consume();
            return new NodeQualifierMember(qualifier, consume().value); 
        }
        return new NodeQualifier(qualifier);
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
    private NodeLiteral parseLiteral() {

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
        return cache.size() > 0 ? cache.remove(0) : nextToken();
    }

    private Token peek(int offset) {
        while (cache.size() <= offset) {
            cache.add(nextToken());
        }
        return cache.get(offset);
    }

    private boolean tryConsume(Token token) {
        final boolean success;
        if ((success = peek() == token))
            consume();
        return success;
    }

    /***************************************************************************
     * Tokenisation
     * 
     * The Tokeniser process every character from the input expression individually
     * and produces tokens based on what it sees and in what order. It keeps no
     * track of previous tokens or states and blindly moves forward along the 
     * expression string. Context does not matter to the Tokeniser.
     * 
     * That being said however, it is aware of the different ways some tokens are
     * generated from combinations of large amounts of characters. Take for example,
     * the StringLiteral token type. The Tokeniser reads a single or double quote
     * character and deduces that all the characters following belong to a string
     * until a matching quote character is found. Some operators are composed of 
     * two special characters in a row, and different literals have special ways
     * of being produced.
     * 
     * All tokens contain a string value and a token type, some tokens have 
     * precedence values. Most tokens are standard ones that can be found in the
     * Token class. That helps later on when processing them, both in memory and
     * indentification.
     **************************************************************************/
    private Token nextToken() {
        final StringBuffer buffer = new StringBuffer();
        String state = "Waiting";
        while (peekChar() != Token.EOF || buffer.length() > 0) {

            // Building a String Literal
            if (state.startsWith("String Literal")) {
                if (state.endsWith("Double") && tryConsumeChar(Token.DoubleQuote)) {
                    final String bufferValue = buffer.toString();
                    buffer.setLength(0);
                    return Token.makeToken(bufferValue, TokenType.StringLiteral);
                }
                else if (state.endsWith("Single") && tryConsumeChar(Token.SingleQuote)) {
                    final String bufferValue = buffer.toString();
                    buffer.setLength(0);
                    return Token.makeToken(bufferValue, TokenType.StringLiteral);
                }
                else {
                    buffer.append(consumeChar());
                }
            }

            // Building a Qualifier or Keyword
            else if (peekChar() == '_' || alpha(peekChar()) || (alphanum(peekChar()) && state == "Word")) {
                buffer.append(consumeChar());     
                state = "Word";
            }
            
            // Building Date or Number Literal
            else if (numeric(peekChar()) && (state == "Decimal" || state == "Date")) {
                buffer.append(consumeChar());
            }

            // Building a Number Literal
            else if (numeric(peekChar()) && (state == "Waiting" || state == "Number")) {
                buffer.append(consumeChar());
                state = "Number";
            } 
            
            // Building a Date Literal
            else if (peekChar() == '/' && (state == "Number" ||  state == "Date")) {
                buffer.append(consumeChar());  
                state = "Date";
            }

            // Building a Decimal Number Literal
            else if (peekChar() == '.' && (state == "Number")) {
                buffer.append(consumeChar());  
                state = "Decimal";
            }

            else {
                // If the buffer is full, it means a Keyword or Literal is ready to be tokenised
                if (buffer.length() > 0) {
                    final String bufferValue = buffer.toString();
                    if (state == "Number") {
                        this.answer |= bufferValue.equals("42"); // Life, The Universe, and Everything
                        return Token.makeToken(bufferValue, TokenType.NumberLiteral);
                    }

                    else if (state == "Date") {
                        return Token.makeToken(bufferValue, TokenType.DateLiteral);
                    }

                    else {
                        switch (bufferValue) {
                            case "and": return Token.And;
                            case "or": return Token.Or;
                            case "not": return Token.Not;
                            case "empty": return Token.Empty;
                            case "true": return Token.True;
                            case "false": return Token.False;
                            default: return Token.makeToken(bufferValue, TokenType.Qualifier);
                        }
                    }
                }

                // Otherwise, token could be an operator or punctuation token
                final Token singleChar = consumeSingleCharToken();
                if (singleChar != null) return singleChar;

                else if (tryConsumeChar(Token.Less)) {
                    if (tryConsumeChar(Token.Equals)) {
                        return Token.LessEqual;
                    } else {
                        return Token.Less;
                    }
                }

                else if (tryConsumeChar(Token.Greater)) {
                    if (tryConsumeChar(Token.Equals)) {
                        return Token.GreaterEqual;
                    } else {
                        return Token.Greater;
                    }
                }

                else if (tryConsumeChar(Token.Exclaim)) {
                    if (tryConsumeChar(Token.Equals)) {
                        return Token.NotEquals;
                    } else {
                        return Token.Exclaim;
                    }
                }

                // Otherwise, token could signal the start of a string literal
                else if (tryConsumeChar(Token.DoubleQuote)) {
                    state = "String Literal Double";
                }

                else if (tryConsumeChar(Token.SingleQuote)) {
                    state = "String Literal Single";
                }

                // Otherwise, token is a space and can be ignored.
                else if (space(peekChar())) {
                    consumeChar();
                }

                // Finally, if token has not been returned, the character cannot be identified.
                // This is likely unreachable.
                else {
                    throw new RuntimeException("Invalid token: " + peekChar());
                }
            }
        }

        // No more tokens can be found, and an End of Tokens token will be returned.
        return Token.EOT;
    }

    // List of all single character tokens for simplicity.
    private static final List<Token> singles = List.of(
        Token.Ampersand, Token.Pipe, Token.Tilde, Token.Plus, Token.Minus, Token.Star, Token.Div, Token.Mod, Token.Power,
        Token.Equals, Token.At, Token.Underscore, Token.Hashtag, Token.Question, Token.Comma, Token.Colon, Token.Period, 
        Token.Semi, Token.BackSlash, Token.OpenParen, Token.CloseParen, Token.OpenCurly, Token.CloseCurly, Token.OpenSquare,
        Token.CloseSquare
    );

    private Token consumeSingleCharToken() {
        for (Token t : singles) {
            if (tryConsumeChar(t)) {
                return t;
            }
        }
        return null;
    }

    /*
     * Character Handling
     * 
     * A lot simpler than the token handling functions seen above. This is thanks
     * to the fact that all Characters are present and easily accessible. A simple
     * pointer address where the current character being read is, and the expression
     * string itself is never modified.
     */
    private boolean tryConsumeChar(Token token) {
        final boolean success;
        if (success = String.valueOf(peekChar()).equals(token.value)) {
            consumeChar();
        }
        return success;
    }
    
    private char peekChar() {
        return expression.charAt(charIndex);
    }

    private char consumeChar() {
        return expression.charAt(charIndex++);
    }

    // Helper character identification functions.
    private boolean alphanum(Character c) {
        return alpha(c) || numeric(c);
    }

    private boolean numeric(Character c) {
        return (c >= '0' && c <= '9');
    }

    private boolean alpha(Character c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private boolean space(Character c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\b';
    }

    // Helper print functions to be able to view the state of the tree at any point
    public static <T> String print(T bexpr) {
        return print(bexpr, 0);
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> String print(T bexpr, int tab) {
        if (bexpr instanceof OpBinary) {
            return printBin((OpBinary) bexpr, tab);
        }
        
        else if (bexpr instanceof OpUnary) {
            return printUn((OpUnary) bexpr, tab);
        }
        
        else if (bexpr instanceof OpValue) {
            return printVal((OpValue) bexpr, tab);
        }

        return "";
    }

    private static <T> String printBin(OpBinary<T> bexpr, int tab) {
        String padding = new String(new char[tab]).replace('\0', ' ');
        String text = padding + bexpr.nodeName() + "\n";
        text += print(bexpr.lhs, tab + 2) + "\n";
        text += print(bexpr.rhs, tab + 2);
        return text;
    }

    private static <T> String printUn(OpUnary<T> expr, int tab) {
        String padding = new String(new char[tab]).replace('\0', ' ');
        String text = padding + expr.nodeName() + "\n";
        return text + print(expr.val, tab + 2);
    }

    private static <T> String printVal(OpValue<T> expr, int tab) {
        String padding = new String(new char[tab]).replace('\0', ' ');
        return padding + expr.nodeName() + ": " + expr.val;
    }
}

/*
 * Token Types
 * 
 * Used in the parser for a lot of manipulation, it helps identify quickly if a 
 * Token belongs in an expression or not. Note that there may be unused types in
 * the current implementation of the Tokeniser.
 */
enum TokenType{
    Qualifier, Keyword, Punctuation, WhiteSpace,
    NumberLiteral, StringLiteral, BooleanLiteral, DateLiteral, 
    Arithmetic, Equality, BooleanArithmetic, BitArithmetic;
}

/*
 * Token
 * 
 * A Token is a single coherent ordered sequence of characters. Additional information
 * can be stored in a Token about its type and precedence (if applicable). Tokens
 * that share the same ordered sequence of characters are NOT necessarily identical.
 * They must also share the same type and precedence. Tokens are also immutable.
 */
class Token {

    public final String value;
    public final TokenType type;
    public final int precedence;

    public static final Token Empty = new Token("empty", TokenType.Keyword);
    public static final Token Not = new Token("not", TokenType.Keyword);
    public static final Token True = new Token("true", TokenType.BooleanLiteral);
    public static final Token False = new Token("false", TokenType.BooleanLiteral);
    public static final Token And = new Token("and", TokenType.BooleanArithmetic);
    public static final Token Or = new Token("or", TokenType.BooleanArithmetic);
    public static final Token ShiftLeft = new Token("<<", TokenType.BitArithmetic);
    public static final Token ShiftRight = new Token(">>", TokenType.BitArithmetic);
    public static final Token Ampersand = new Token("&", TokenType.BitArithmetic);
    public static final Token Pipe = new Token("|", TokenType.BitArithmetic);
    public static final Token Tilde = new Token("~", TokenType.BitArithmetic);
    public static final Token Plus = new Token("+", TokenType.Arithmetic, 1);
    public static final Token Minus = new Token("-", TokenType.Arithmetic, 2);
    public static final Token Star = new Token("*", TokenType.Arithmetic, 3);
    public static final Token Div = new Token("/", TokenType.Arithmetic, 3);
    public static final Token Mod = new Token("%", TokenType.Arithmetic, 3);
    public static final Token Power = new Token("^", TokenType.Arithmetic, 4);
    public static final Token Equals = new Token("=", TokenType.Equality);
    public static final Token NotEquals = new Token("!=", TokenType.Equality);
    public static final Token Greater = new Token(">", TokenType.Equality);
    public static final Token Less = new Token("<", TokenType.Equality);
    public static final Token GreaterEqual = new Token(">=", TokenType.Equality);
    public static final Token LessEqual = new Token("<=", TokenType.Equality);
    public static final Token At = new Token("@", TokenType.Punctuation);
    public static final Token Underscore = new Token("_", TokenType.Punctuation);
    public static final Token Hashtag = new Token("#", TokenType.Punctuation);
    public static final Token Exclaim = new Token("!", TokenType.Punctuation);
    public static final Token Question = new Token("?", TokenType.Punctuation);
    public static final Token Comma = new Token(",", TokenType.Punctuation);
    public static final Token Colon = new Token(":", TokenType.Punctuation);
    public static final Token Period = new Token(".", TokenType.Punctuation);
    public static final Token Semi = new Token(";", TokenType.Punctuation);
    public static final Token BackSlash = new Token("\\", TokenType.Punctuation);
    public static final Token OpenParen = new Token("(", TokenType.Punctuation);
    public static final Token CloseParen = new Token(")", TokenType.Punctuation);
    public static final Token OpenCurly = new Token("{", TokenType.Punctuation);
    public static final Token CloseCurly = new Token("}", TokenType.Punctuation);
    public static final Token OpenSquare = new Token("[", TokenType.Punctuation);
    public static final Token CloseSquare = new Token("]", TokenType.Punctuation);
    public static final Token DoubleQuote = new Token("\"", TokenType.Punctuation);
    public static final Token SingleQuote = new Token("\'", TokenType.Punctuation);

    public static final Token EOT = Token.makeToken("End", TokenType.Keyword);
    public static final char EOF = '\0';

    private Token(String val, TokenType type) {
        this(val, type, 0);
    }

    private Token(String val, TokenType type, int precedence) {
        this.value = val;
        this.type = type;
        this.precedence = precedence;
    }

    boolean hasValue() {
        return !value.isBlank();
    }

    static Token makeToken(String name, TokenType type) {
        return new Token(name, type);
    }

    @Override
    public String toString() {
        return this.value;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Token)) {
            return false;
        }

        Token token = (Token) other;
        return this.value.equals(token.value) && this.type == token.type && this.precedence == token.precedence;
    }

    @Override
    public int hashCode() {
        // Not sure how collision free this is. -SMG
        return (this.value.hashCode() + this.type.hashCode()) ^ this.precedence;
    }

}

/*
 * Node classes
 * 
 * All nodes used by the Parser are objects of the following classes, and their 
 * structure should resemble the productions seen above. Note however that all
 * nodes eventually extend the NodeBExpr class regardless of whether or not they
 * can be evaluated as a boolean. This does make the interpreter harder to work
 * with, but it cuts down on the number of nodes required to describe a simple
 * expression. Take for example the input expression 'true'. If a strong node 
 * structure is enfored it would take the form:
 * 
 * NodeBExpr
 *   NodeComp
 *     NodeTerm
 *        NodeLiteral
 *          NodeBoolean: true
 * 
 * The way it is currently written, only the final node is needed for a valid
 * tree in the eyes of the Interpreter. Perhaps some rework in this area might be
 * required if this language grows further (which it will!).
 */
interface NodeBExpr {
    public String nodeName();
}
interface NodeComp {
    public String nodeName();
}
interface NodeTerm {
    public String nodeName();
}
interface NodeVariable {
    public String nodeName();
}
interface NodeLiteral {
    public String nodeName();
}

abstract class NodeBinaryComp extends OpBinary<NodeTerm> implements NodeComp {
    NodeBinaryComp(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
}

abstract class OpBinary<T> {
    final T lhs;
    final T rhs;

    OpBinary(T lhs, T rhs) {
        this.lhs = lhs;
        this.rhs = rhs;
    }
    
    public String nodeName() {
        return "BinOp";
    }

    @Override
    public String toString() {
        return Parser.print(this);
    }
}

abstract class OpUnary<T> {
    final T val;
    OpUnary(T val) {
        this.val = val;
    }
    
    public String nodeName() {
        return "UnOp";
    }
    
    @Override
    public String toString() {
        return Parser.print(this);
    }
}

abstract class OpValue<T> {
    final T val;
    OpValue(T val) {
        this.val = val;
    }
    
    public String nodeName() {
        return "ValOp";
    }
    
    @Override
    public String toString() {
        return Parser.print(this);
    }
}

class NodeBExprAnd extends OpBinary<NodeBExpr> implements NodeBExpr {
    public NodeBExprAnd(NodeBExpr lhs, NodeBExpr rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "And";
    }
}

class NodeBExprOr extends OpBinary<NodeBExpr> implements NodeBExpr {
    public NodeBExprOr(NodeBExpr lhs, NodeBExpr rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return "Or";
    }
}

class NodeBExprNot extends OpUnary<NodeBExpr> implements NodeBExpr {
    public NodeBExprNot(NodeBExpr val) {
        super(val);
    }
    
    public String nodeName() {
        return "Not";
    }
}

class NodeBExprComp extends OpUnary<NodeComp> implements NodeBExpr {
    public NodeBExprComp(NodeComp val) {
        super(val);
    }
    
    public String nodeName() {
        return "BExprComp";
    }
}

class NodeCompGreaterThan extends NodeBinaryComp {
    public NodeCompGreaterThan(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return ">";
    }
}

class NodeCompGreaterThanEqual extends NodeBinaryComp {
    public NodeCompGreaterThanEqual(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return ">=";
    }
}

class NodeCompLessThan extends NodeBinaryComp {
    public NodeCompLessThan(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return "<";
    }
}

class NodeCompLessThanEqual extends NodeBinaryComp {
    public NodeCompLessThanEqual(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return "<=";
    }
}

class NodeCompEqual extends NodeBinaryComp {
    public NodeCompEqual(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return "Eq";
    }
}

class NodeCompNotEqual extends NodeBinaryComp {
    public NodeCompNotEqual(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
    
    public String nodeName() {
        return "Neq";
    }
}

class NodeCompTerm extends OpUnary<NodeTerm> implements NodeComp {
    public NodeCompTerm(NodeTerm val) {
        super(val);
    }
    
    public String nodeName() {
        return "CompTerm";
    }
}

class NodeTermLiteral extends OpUnary<NodeLiteral> implements NodeTerm {
    public NodeTermLiteral(NodeLiteral val) {
        super(val);
    }
    
    public String nodeName() {
        return "TermLiteral";
    }
}

class NodeTermVariable extends OpUnary<NodeVariable> implements NodeTerm {
    public NodeTermVariable(NodeVariable val) {
        super(val);
    }
    
    public String nodeName() {
        return "TermVariable";
    }
}


class NodeQualifier extends OpUnary<String> implements NodeVariable {
    NodeQualifier(String val) {
        super(val);
    }
    
    public String nodeName() {
        return "Qualifier";
    }
}

class NodeQualifierMember extends OpBinary<String> implements NodeVariable {
    public NodeQualifierMember(String qualifier, String member) {
        super(qualifier, member);
    }
    
    public String nodeName() {
        return "Member";
    }
}

class NodeNumber extends OpValue<Double> implements NodeLiteral {
    public NodeNumber(Double val) {
        super(val);
    }

    public String nodeName() {
        return "Number";
    }
}

class NodeDate extends OpValue<Date> implements NodeLiteral {
    public NodeDate(Date val) {
        super(val);
    }
    
    public String nodeName() {
        return "Date";
    }
}

class NodeString extends OpValue<String> implements NodeLiteral {
    public NodeString(String val) {
        super(val);
    }
    
    public String nodeName() {
        return "String";
    }
}

class NodeBoolean extends OpValue<Boolean> implements NodeLiteral {
    public NodeBoolean(Boolean val) {
        super(val);
    }
    
    public String nodeName() {
        return "Boolean";
    }
}

class NodeEmpty extends OpValue<Void> implements NodeLiteral {
    public NodeEmpty() {
        super(null);
    }
    
    public String nodeName() {
        return "Empty";
    }
}


abstract class NodeArithmetic extends OpBinary<NodeTerm> implements NodeTerm {
    NodeArithmetic(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }
}

class NodeAdd extends NodeArithmetic {
    public NodeAdd(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "Add";
    }
}

class NodeSub extends NodeArithmetic {
    public NodeSub(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "Sub";
    }
}

class NodeMul extends NodeArithmetic {
    public NodeMul(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "Mul";
    }
}

class NodeDiv extends NodeArithmetic {
    public NodeDiv(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "Div";
    }
}

class NodeMod extends NodeArithmetic {
    public NodeMod(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "Mod";
    }
}

class NodePow extends NodeArithmetic {
    public NodePow(NodeTerm lhs, NodeTerm rhs) {
        super(lhs, rhs);
    }

    public String nodeName() {
        return "Pow";
    }
}