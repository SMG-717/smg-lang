package com.forenzix.interpreter;

import java.util.List;

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
                    final String bufferValue = escape(buffer.toString());
                    buffer.setLength(0);
                    return Token.makeToken(bufferValue, TokenType.StringLiteral);
                }
                else if (state.endsWith("Single") && tryConsume(Token.SingleQuote)) {
                    final String bufferValue = escape(buffer.toString());
                    buffer.setLength(0);
                    return Token.makeToken(bufferValue, TokenType.StringLiteral);
                }
                else if (tryConsume(Token.Newline)) {
                    // Forbid multiline strings in source code
                    throw new RuntimeException("Unexpected new line in string literal");
                }
                else {
                    buffer.append(consume());
                }
            }

            // Building a comment
            else if (state == "Comment") {
                if (tryConsume(Token.Newline)) {
                    buffer.append("\n");
                    return Token.makeToken(buffer.toString(), TokenType.Comment);
                }
                else if (peek() == Token.EOF) {
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
            else if (numeric(peek()) && (state == "Decimal")) {
                buffer.append(consume());
            }

            // Building a Number Literal
            else if (numeric(peek()) && (state == "Waiting" || state == "Number")) {
                buffer.append(consume());
                state = "Number";
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
                    if (state == "Number" || state == "Decimal") {
                        return Token.makeToken(bufferValue, TokenType.NumberLiteral);
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

                // Otherwise, token could be a multi character operator
                final Token operator = consumeOperator();
                if (operator != null) return operator;

                // Otherwise, token could be a single character operator or punctuation token
                final Token singleChar = consumeSingleCharToken();
                if (singleChar != null) return singleChar;

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

    
    private String escape(String text) {
        text = text.replaceAll("\\\\n", "\n");
        text = text.replaceAll("\\\\t", "\t");
        text = text.replaceAll("\\\\r", "\r");
        text = text.replaceAll("\\\\b", "\b");
        text = text.replaceAll("\\\\\"", "\"");
        text = text.replaceAll("\\\\\'", "\'");

        return text;
    }

    // List of all single character tokens for simplicity.
    private static final List<Token> singles = List.of(
        Token.Ampersand, Token.Pipe, Token.Tilde, Token.Plus, Token.Hyphen, 
        Token.Asterisk, Token.ForwardSlash, Token.Percent, Token.Caret, Token.At, 
        Token.Underscore, Token.Question, Token.Comma, Token.Colon, Token.Newline, 
        Token.Period, Token.SemiColon, Token.BackSlash, Token.OpenParen, 
        Token.CloseParen, Token.OpenCurly, Token.CloseCurly, Token.OpenSquare, 
        Token.CloseSquare, Token.Less, Token.Exclaim, Token.Greater, Token.EqualSign
    );

    private Token consumeSingleCharToken() {
        for (Token t : singles) {
            if (tryConsume(t)) {
                return t;
            }
        }
        return null;
    }

    // List of all operator tokens for simplicity.
    private static final List<Token> operators = List.of(
        Token.GreaterEqual, Token.LessEqual, Token.Equals, Token.NotEquals, 
        Token.ShiftLeft, Token.ShiftRight, Token.PlusEqual, Token.MultiplyEqual, 
        Token.SubtractEqual, Token.DivideEqual, Token.ModEqual, Token.AndEqual, Token.OrEqual
    );

    private Token consumeOperator() {
        for (Token t : operators) {
            if (tryConsume(t)) {
                return t;
            }
        }
        return null;
    }

    private static final List<Token> keywords = List.of(
        Token.Empty, Token.If, Token.Else, Token.While, Token.Let, Token.True, Token.False,
        Token.And, Token.Not, Token.Xor, Token.Define, Token.Return, Token.Or, Token.For, Token.In
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
        if (success = peekLots(token.value.length()).equals(token.value)) {
            consumeLots(token.value.length());
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
        return charIndex == expression.length() ? Token.EOF : expression.charAt(charIndex);
    }

    private String peekLots(int amount) {
        return charIndex > expression.length() + amount ? String.valueOf(Token.EOF) : expression.substring(charIndex, charIndex + amount);
    }

    private char consume() {
        return charIndex == expression.length() ? Token.EOF : expression.charAt(charIndex++);
    }

    private String consumeLots(int amount) {        
        return charIndex > expression.length() + amount ? String.valueOf(Token.EOF) : expression.substring(charIndex, charIndex += amount);
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
