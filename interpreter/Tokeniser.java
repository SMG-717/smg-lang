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
        while (peek() != Token.EOF || buffer.length() > 0) {

            // Building a String Literal
            if (state.startsWith("String Literal")) {
                if (state.endsWith("Double") && tryConsume(Token.DoubleQuote)) {
                    final String bufferValue = buffer.toString();
                    buffer.setLength(0);
                    return Token.makeToken(bufferValue, TokenType.StringLiteral);
                }
                else if (state.endsWith("Single") && tryConsume(Token.SingleQuote)) {
                    final String bufferValue = buffer.toString();
                    buffer.setLength(0);
                    return Token.makeToken(bufferValue, TokenType.StringLiteral);
                }
                else {
                    buffer.append(consume());
                }
            }

            // Building a comment
            else if (state == "Comment") {
                if (tryConsume(Token.Newline)) {
                    return Token.makeToken(buffer.toString(), TokenType.Comment);
                }
                else {
                    buffer.append(consume());
                }
            }

            // Building a Qualifier or Keyword
            else if (peek() == '_' || alpha(peek()) || (alphanum(peek()) && state == "Word")) {
                buffer.append(consume());     
                state = "Word";
            }
            
            // Building Date or Number Literal
            else if (numeric(peek()) && (state == "Decimal" || state == "Date")) {
                buffer.append(consume());
            }

            // Building a Number Literal
            else if (numeric(peek()) && (state == "Waiting" || state == "Number")) {
                buffer.append(consume());
                state = "Number";
            } 
            
            // Building a Date Literal
            else if (peek() == '/' && (state == "Number" ||  state == "Date")) {
                buffer.append(consume());  
                state = "Date";
            }

            // Building a Decimal Number Literal
            else if (peek() == '.' && (state == "Number")) {
                buffer.append(consume());  
                state = "Decimal";
            }

            else {
                // If the buffer is full, it means a Keyword or Literal is ready to be tokenised
                if (buffer.length() > 0) {
                    final String bufferValue = buffer.toString();
                    if (state == "Number") {
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

                // Otherwise, token could be a single character operator or punctuation token
                final Token singleChar = consumeSingleCharToken();
                if (singleChar != null) return singleChar;

                // Otherwise, The token could be a single or double character operator
                else if (tryConsume(Token.Less)) {
                    return tryConsume(Token.EqualSign) ? Token.LessEqual : Token.Less;
                }

                else if (tryConsume(Token.EqualSign)) {
                    return tryConsume(Token.EqualSign) ? Token.Equals : Token.EqualSign;
                }

                else if (tryConsume(Token.Greater)) {
                    return tryConsume(Token.EqualSign) ? Token.GreaterEqual : Token.Greater;
                }

                else if (tryConsume(Token.Exclaim)) {
                    return tryConsume(Token.EqualSign) ? Token.NotEquals : Token.Exclaim;
                }

                // Otherwise, token could signal the start of a string literal
                else if (tryConsume(Token.DoubleQuote)) {
                    state = "String Literal Double";
                }

                else if (tryConsume(Token.SingleQuote)) {
                    state = "String Literal Single";
                }

                // Otherwise, token could be the start of a comment
                else if (tryConsume(Token.Hashtag)) {
                    state = "Comment";
                }

                // Otherwise, token is a space and can be ignored.
                else if (space(peek())) {
                    consume();
                }

                // Finally, if no token has been returned, the character cannot be identified.
                // This is likely unreachable.
                else {
                    throw new RuntimeException("Invalid token: " + peek());
                }
            }
        }

        // No more tokens can be found, and an End of Tokens token will be returned.
        return Token.EOT;
    }

    // List of all single character tokens for simplicity.
    private static final List<Token> singles = List.of(
        Token.Ampersand, Token.Pipe, Token.Tilde, Token.Plus, Token.Hyphen, 
        Token.Asterisk, Token.ForwardSlash, Token.Percent, Token.Caret, Token.At, 
        Token.Underscore, Token.Question, Token.Comma, Token.Colon, Token.Newline, 
        Token.Period, Token.SemiColon, Token.BackSlash, Token.OpenParen, 
        Token.CloseParen, Token.OpenCurly, Token.CloseCurly, Token.OpenSquare, 
        Token.CloseSquare
    );

    private Token consumeSingleCharToken() {
        for (Token t : singles) {
            if (tryConsume(t)) {
                return t;
            }
        }
        return null;
    }

    private static final List<Token> keywords = List.of(
        Token.Empty,
        Token.If,
        Token.Else,
        Token.While,
        Token.Let,
        Token.True,
        Token.False,
        Token.And,
        Token.Not,
        Token.Xor,
        Token.Define,
        Token.Return,
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
    private boolean tryConsume(Token token) {
        final boolean success;
        if (success = String.valueOf(peek()).equals(token.value)) {
            consume();
        }
        return success;
    }

    /*
     * Character Handling
     * 
     * Keep track of our positions in the program string and advance only when
     * needed. The tokeniser can peek() to see what character is currently being
     * read and available to pick up for processing. To progress forward the 
     * tokeniser can consume() the character.
     * 
     * Other helper functions exist to make peeking and consuming easier, like
     * tryConsume(), and getKeyword().
     */
    
    private char peek() {
        return expression.charAt(charIndex);
    }

    private char consume() {
        return expression.charAt(charIndex++);
    }
    
    public void reset() {
        charIndex = 0;
    }

    public String toString() {
        return expression;
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
}

/*
 * Token Types
 * 
 * Used in the parser for a lot of manipulation, it helps identify quickly if a 
 * Token belongs in an expression or not. Note that there may be unused types in
 * the current implementation of the Tokeniser.
 */
enum TokenType{
    Qualifier, Keyword, Punctuation, WhiteSpace, Comment,
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
    public static final Token While = new Token("while", TokenType.Keyword);
    public static final Token Let = new Token("let", TokenType.Keyword);
    public static final Token Define = new Token("define", TokenType.Keyword);
    public static final Token Return = new Token("return", TokenType.Keyword);
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
    public static final Token Equals = new Token("==", TokenType.BinaryArithmetic, 3);
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
    public static final Token Exclaim = new Token("!", Set.of(TokenType.Punctuation, TokenType.UnaryArithmetic), 0);
    public static final Token Question = new Token("?", TokenType.Punctuation);
    public static final Token Comma = new Token(",", TokenType.Punctuation);
    public static final Token Colon = new Token(":", TokenType.Punctuation);
    public static final Token Period = new Token(".", TokenType.Punctuation);
    public static final Token SemiColon = new Token(";", TokenType.Punctuation);
    public static final Token BackSlash = new Token("\\", TokenType.Punctuation);
    public static final Token OpenParen = new Token("(", TokenType.Punctuation);
    public static final Token CloseParen = new Token(")", TokenType.Punctuation);
    public static final Token OpenCurly = new Token("{", TokenType.Punctuation);
    public static final Token CloseCurly = new Token("}", TokenType.Punctuation);
    public static final Token OpenSquare = new Token("[", TokenType.Punctuation);
    public static final Token CloseSquare = new Token("]", TokenType.Punctuation);
    public static final Token DoubleQuote = new Token("\"", TokenType.Punctuation);
    public static final Token SingleQuote = new Token("\'", TokenType.Punctuation);
    public static final Token EqualSign = new Token("=", TokenType.Punctuation);

    public static final Token Newline = new Token("\n", TokenType.WhiteSpace);
    public static final Token CarriageReturn = new Token("\r", TokenType.WhiteSpace);
    public static final Token Tab = new Token("\t", TokenType.WhiteSpace);
    public static final Token BackSpace = new Token("\b", TokenType.WhiteSpace);

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