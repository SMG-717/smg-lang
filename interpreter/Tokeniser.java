package interpreter;

import java.util.List;
import java.util.Set;

public class Tokeniser {

    private final String expression;
    private int charIndex = 0;
    public Tokeniser(String input) {
        expression = input;
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

    public Token nextToken() {
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
                        // this.answer |= bufferValue.equals("42"); // Life, The Universe, and Everything
                        return Token.makeToken(bufferValue, TokenType.NumberLiteral);
                    }

                    else if (state == "Date") {
                        return Token.makeToken(bufferValue, TokenType.DateLiteral);
                    }

                    else {
                        final Token keyword = getKeyword(bufferValue);
                        if (keyword != null) {
                            return keyword;
                        }
                        else {
                            return Token.makeToken(bufferValue, TokenType.Qualifier);
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
        Token.Ampersand, Token.Pipe, Token.Tilde, Token.Plus, Token.Hyphen, Token.Asterisk, Token.ForwardSlash, Token.Percent, 
        Token.Caret, Token.Equals, Token.At, Token.Underscore, Token.Hashtag, Token.Question, Token.Comma, Token.Colon, 
        Token.Period, Token.Semi, Token.BackSlash, Token.OpenParen, Token.CloseParen, Token.OpenCurly, Token.CloseCurly, 
        Token.OpenSquare, Token.CloseSquare
    );

    private Token consumeSingleCharToken() {
        for (Token t : singles) {
            if (tryConsumeChar(t)) {
                return t;
            }
        }
        return null;
    }

    private static final List<Token> keywords = List.of(
        Token.Empty,
        Token.If,
        Token.Else,
        Token.Let,
        Token.True,
        Token.False,
        Token.And,
        Token.Not,
        Token.Xor,
        Token.Or
    );

    private Token getKeyword(String value) {
        for (Token t : keywords) {
            if (t.value.equals(value)) {
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

    public void reset() {
        charIndex = 0;
    }

    public String toString() {
        return expression;
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
    BinaryArithmetic, UnaryArithmetic;
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
    public final Set<TokenType> types;
    public final int precedence;
    public final boolean rightassoc;

    public static final Token Empty = new Token("empty", TokenType.Keyword);
    public static final Token If = new Token("if", TokenType.Keyword);
    public static final Token Else = new Token("else", TokenType.Keyword);
    public static final Token Let = new Token("let", TokenType.Keyword);
    public static final Token True = new Token("true", TokenType.BooleanLiteral);
    public static final Token False = new Token("false", TokenType.BooleanLiteral);
    
    public static final Token Caret = new Token("^", TokenType.BinaryArithmetic, 8, true);
    public static final Token Asterisk = new Token("*", TokenType.BinaryArithmetic, 7);
    public static final Token ForwardSlash = new Token("/", TokenType.BinaryArithmetic, 7);
    public static final Token Percent = new Token("%", TokenType.BinaryArithmetic, 7);
    public static final Token Plus = new Token("+", TokenType.BinaryArithmetic, 6);
    public static final Token Hyphen = new Token("-", Set.of(TokenType.BinaryArithmetic, TokenType.UnaryArithmetic), 6);
    public static final Token ShiftLeft = new Token("<<", TokenType.BinaryArithmetic, 5);
    public static final Token ShiftRight = new Token(">>", TokenType.BinaryArithmetic, 5);
    public static final Token Greater = new Token(">", TokenType.BinaryArithmetic, 4);
    public static final Token Less = new Token("<", TokenType.BinaryArithmetic, 4);
    public static final Token GreaterEqual = new Token(">=", TokenType.BinaryArithmetic, 4);
    public static final Token LessEqual = new Token("<=", TokenType.BinaryArithmetic, 4);
    public static final Token Equals = new Token("=", TokenType.BinaryArithmetic, 3);
    public static final Token NotEquals = new Token("!=", TokenType.BinaryArithmetic, 3);
    public static final Token Ampersand = new Token("&", TokenType.BinaryArithmetic, 2);
    public static final Token Pipe = new Token("|", TokenType.BinaryArithmetic, 2);
    public static final Token Xor = new Token("xor", TokenType.BinaryArithmetic, 2);
    public static final Token And = new Token("and", TokenType.BinaryArithmetic, 1);
    public static final Token Or = new Token("or", TokenType.BinaryArithmetic, 1);
    public static final Token Not = new Token("not", TokenType.UnaryArithmetic);
    public static final Token Tilde = new Token("~", TokenType.UnaryArithmetic);

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

    private Token(String val, TokenType type, int precedence, boolean rightassoc) {
        this(val, Set.of(type), precedence, rightassoc);
    }

    private Token(String val, TokenType type, int precedence) {
        this(val, Set.of(type), precedence, false);
    }

    private Token(String val, Set<TokenType> types, int precedence) {
        this(val, types, precedence, false);
    }

    private Token(String val, Set<TokenType> types, int precedence, boolean rightassoc) {
        this.value = val;
        this.types = Set.copyOf(types);
        this.precedence = precedence;
        this.rightassoc = rightassoc;
    }

    boolean hasValue() {
        return !value.isBlank();
    }

    static Token makeToken(String name, TokenType type) {
        return new Token(name, type);
    }

    boolean is(TokenType type) {
        return types.contains(type);
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
        return this.value.equals(token.value) && this.types.equals(token.types) && this.precedence == token.precedence;
    }

    @Override
    public int hashCode() {
        // Not sure how collision free this is. -SMG
        return (this.value.hashCode() + this.types.hashCode()) ^ this.precedence;
    }

}