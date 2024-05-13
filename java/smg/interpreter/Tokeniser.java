package smg.interpreter;

import java.util.LinkedList;

/**
 * The tokeniser reads the input program string one character at a time, and 
 * turns them into tokens according to a few simple rules. 
 * <p>
 * Tokens can be of known size, type and contents at compile time. All tokens of
 * this kind are stored as static final fields in the Token class and are called
 * from here on out as 'definite' tokens. Examples of such are operators like 
 * '+' and '-', keywords like 'function' and 'continue', and punctuation like 
 * '{' and ';'. A special definite token marks the End of Tokens (EOT) and is
 * always presented when the tokeniser reaches the end of the program string.
 * <p>
 * If a token is not known at compile time it is called an indefinite token. It
 * is built by collecting a bunch of characters until an end marker is reached
 * and based on the contents and starting point are assigned an specifc token
 * type. Indefinite token types here are Qualifiers, String Literals, Number
 * Literals, and Comments.
 * <p>
 * The rules for indefinite token parsing are as follows:
 * <pre>
 * c = peek() (current character)
 * If c is Hashtag loop:
 *   Add next char to buffer
 *   If char is NewLine or EOT:
 *     return buffer contents as Comment Token
 * 
 * Otherwise if c is SingleQuote loop:
 *   If next char is SingleQuote:
 *     return buffer contents as String Literal Token
 *   Otherwise if next char is NewLine, forbid tokenisation.
 *   Otherwise add next char to buffer
 * 
 * Otherwise if c is DoubleQuote loop:
 *   If next char is DoubleQuote:
 *     return buffer contents as String Literal Token
 *   Otherwise if next char is NewLine, forbid tokenisation.
 *   Otherwise add next char to buffer
 * 
 * Otherwise if c is Underscore or Alphabetical:
 *   Add c to buffer
 *   while next char is Underscore or Alphanumeric:
 *     Add next char to buffer
 *   return buffer contents as Qualifier Token
 * 
 * Otherwise if c is Numeric:
 *   // Not guaranteed to always generate parsable numeric values.
 *   Add c to buffer
 *   while next char is Period or Numeric:
 *     Add next char to buffer
 *   return buffer contents as Number Literal Token
 * 
 * Otherwise c is not a recognised start of an indefinite token.
 * </pre>
 */
public class Tokeniser {

    // Current location of the Tokeniser on the progam string
    private int loc = 0;

    // The program which contains all of our instructions
    private final String program;
    public Tokeniser(String input) { program = input; }
    
    // Tokenisation states
    private final int 
        WAITING = 0, 
        S_STR_LIT = 1, 
        D_STR_LIT = 2, 
        COMMENT = 3, 
        NUMBER = 4, 
        WORD = 5;

    // Get next token
    public Token nextToken() {

        // Buffer stores a sequence of characters which would later become 
        // literals, comments, etc.
        final StringBuffer buffer = new StringBuffer();

        // Initial state is waiting
        int state = WAITING;
        while (peek() != Token.EOF || buffer.length() > 0) {
            switch (state) {

                // Building a String Literal
                case S_STR_LIT:
                case D_STR_LIT:

                    // Strings must end in the correct token.
                    if ((state == S_STR_LIT && tryConsume(Token.SingleQuote)) || 
                        (state == D_STR_LIT && tryConsume(Token.DoubleQuote))) {
                        final String value = escape(buffer.toString());
                        buffer.setLength(0);
                        return Token.make(value, TokenType.StringLiteral);
                    }

                    // Forbid multiline strings in source code
                    else if (tryConsume(Token.Newline)) 
                        throw error("Unexpected new line in string literal");
                    
                    // Otherwise, everything encountered is part of the string.                    
                    buffer.append(consume());
                    break;
                
                // Building a Comment
                case COMMENT: {

                    // Everything proceeding a hashtag '#' becomes is included.
                    final Token token = tryConsume();
                    buffer.append(token == null ? consume() : token.value);
                    
                    // End the comment if it ends in a new line
                    if (token == Token.Newline || peek() == Token.EOF) 
                        return Token.make(buffer.toString(), TokenType.Comment);
                    
                    break;
                }

                // Waiting for tokens
                case WAITING: 

                    // Attempt to consume a definite token
                    final Token token = tryConsume();

                    // If the token read indicates the start of a string or a 
                    // comment, set the state accordingly
                    state = token == Token.SingleQuote ? S_STR_LIT :
                            token == Token.DoubleQuote ? D_STR_LIT :
                            token == Token.Hashtag ? COMMENT : state;

                    if (state != WAITING) break;

                    // Otherwise, if the token read is not null, it is returned
                    // as the next Token.
                    else if (token != null) return token;

                    // Otherwise, our Token needs to be build up.
                    
                // Building a Qualifier or Keyword
                case WORD:
                    if (peek() == '_' || alpha(peek()) || 
                        (state == WORD && numeric(peek()))) {
                        buffer.append(consume()); 
                        state = WORD;
                        break;
                    }
                    
                // Building a Number Literal
                case NUMBER:
                    if (numeric(peek()) || (state == NUMBER && peek() == '.')) {
                        buffer.append(consume());
                        state = NUMBER;
                        break;
                    }

                // Building Complete
                default:

                    // Buffer full = Literal ready to be tokenised
                    if (buffer.length() > 0) 
                        return Token.make(buffer.toString(), state == NUMBER ? 
                            TokenType.NumberLiteral : TokenType.Qualifier);

                    // Otherwise, if token is a space and can be ignored.
                    else if (space(peek())) consume();
                    
                    // If no character can be identified, throw error.
                    // Likely unreachable.
                    else throw error("Invalid token: %s", peek());
            }
        }

        // No more tokens can be found, and an End of Tokens token is returned.
        return Token.EOT;
    }

    // Collect all tokens into a list for convenience
    public LinkedList<Token> allTokens() {
        reset();
        final LinkedList<Token> tokens = new LinkedList<>();
        
        do tokens.add(nextToken());
        while(tokens.getLast() != Token.EOT);
        
        return tokens;
    }

    /* 
     * Peek and Consume
     * 
     * The most basic operations of the tokeniser and the reason why it works so
     * well. Peeking is when the current character in the program string is read 
     * without changing anything about the tokeniser state. Consuming is reading
     * that character and progressing to the next character in the program.
     * 
     * A consumable is a string of characters from the current character that 
     * can be consumed and turned into a token. It can be arbitrarily long.
     * 
     * An End of File (EOF) is a special character that is encountered at the 
     * end of the program string and it signals the end of tokenisation.
     */

    // Peek the current character. Return EOF if we reach the end.
    private char peek() {
        return loc == program.length() ? Token.EOF : program.charAt(loc);
    }

    // Peek a consumable of fixed length. Return EOF if we reach the end.
    private String peekLots(int amount) {
        return loc + amount > program.length() ? 
            String.valueOf(Token.EOF) : program.substring(loc, loc + amount);
    }

    // Consume the next consumable if it exactly matches the value of a definite 
    // token. The biggest token that matches the consumable wins.
    private Token tryConsume() {
        String c = ""; Token best = null;
        for (Token token : Token.tokenList) {
            final int len = token.value.length(), 
            blen = best == null ? 0 : best.value.length();

            if (len != c.length()) c = peekLots(len);
            if (c.equals(token.value) && len > blen) best = token;
        }

        if (best != null) consumeLots(best.value.length());
        return best;
    }

    // Consume the next consumable if it exactly matches the value of the given
    // token.
    private boolean tryConsume(Token token) {
        final boolean success;
        if (success = peekLots(token.value.length()).equals(token.value)) 
            consumeLots(token.value.length());
        
        return success;
    }

    // Consume the current character. Return EOF at the end.
    private char consume() {
        return loc == program.length() ? Token.EOF : program.charAt(loc++);
    }

    // Consume the next specified amount of characters. Return EOF at the end.
    private String consumeLots(int amount) {
        return loc + amount > program.length() ? 
            String.valueOf(Token.EOF) : program.substring(loc, loc += amount);
    }

    // HELPERS
    private RuntimeException error(String msg, Object... objects) {
        return new RuntimeException(String.format(msg, objects));
    }

    private String escape(String text) {
        return text.replaceAll("\\\\n", "\n")
            .replaceAll("\\\\t", "\t")
            .replaceAll("\\\\r", "\r")
            .replaceAll("\\\\b", "\b")
            .replaceAll("\\\\\"", "\"")
            .replaceAll("\\\\\'", "\'");
    }
    
    public void reset() { loc = 0; }
    public String toString() { return program; }
    private boolean numeric(char c) { return (c >= '0' && c <= '9'); }
    private boolean alpha(char c) { 
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); 
    }
    private boolean space(char c) { 
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\b'; 
    }
}
