package smg.interpreter;

public class Tokeniser {

    // Current location of the Tokeniser on the progam string
    private int loc = 0;

    // The program which contains all of our instructions
    private final String program;
    public Tokeniser(String input) { program = input; }
    
    private final int WAITING = 0, S_STR_LIT = 1, D_STR_LIT = 2, COMMENT = 3, NUMBER = 4, WORD = 5;

    // Get next token
    public Token nextToken() {

        // Buffer stores a sequence of characters which would later become 
        // literals, comments, etc.
        final StringBuffer buffer = new StringBuffer();

        // Initial state is waiting
        int state = WAITING;
        while (peek() != Token.EOF || buffer.length() > 0) {
            switch (state) {
                case S_STR_LIT:
                case D_STR_LIT:
                    if ((state == S_STR_LIT && tryConsume(Token.SingleQuote)) || 
                        (state == D_STR_LIT && tryConsume(Token.DoubleQuote))) {
                        final String bufferValue = escape(buffer.toString());
                        buffer.setLength(0);
                        return Token.make(bufferValue, TokenType.StringLiteral);
                    }

                    else if (tryConsume(Token.Newline)) {
                        // Forbid multiline strings in source code
                        throw new RuntimeException("Unexpected new line in string literal");
                    }
                    
                    buffer.append(consume());
                    break;
                
                case COMMENT: {
                    final Token token = tryConsume();
                    buffer.append(token == null ? consume() : token.value);
                    
                    if (token == Token.Newline || peek() == Token.EOF) 
                        return Token.make(buffer.toString(), TokenType.Comment);
                    
                    break;
                }

                case WAITING: 
                    final Token token = tryConsume();
                    state = token == Token.SingleQuote ? S_STR_LIT :
                            token == Token.DoubleQuote ? D_STR_LIT :
                            token == Token.Hashtag ? COMMENT : state;

                    if (state != WAITING) break;
                    else if (token != null) return token;
                    
                    // Building a Qualifier or Keyword
                case WORD:
                    if (peek() == '_' || alpha(peek()) || (state == WORD && numeric(peek()))) {
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

                // Buffer full = Literal ready to be tokenised
                default:
                    if (buffer.length() > 0) {
                        final String bufferValue = buffer.toString();
                        return Token.make(bufferValue, state == NUMBER ? 
                            TokenType.NumberLiteral : TokenType.Qualifier);
                    }

                    // Otherwise, token is a space and can be ignored.
                    else if (space(peek())) consume();
                    
                    // If no character can be identified, throw error.
                    // Likely unreachable.
                    else throw new RuntimeException("Invalid token: " + peek());
            }
        }

        // No more tokens can be found, and an End of Tokens token is returned.
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

    private Token tryConsume() {
        for (Token token : Token.tokenList) {
            if (peekLots(token.value.length()).equals(token.value)) {
                consumeLots(token.value.length());
                return token;
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
    
    private char peek() {
        return loc == program.length() ? Token.EOF : program.charAt(loc);
    }

    private String peekLots(int amount) {
        return loc + amount > program.length() ? String.valueOf(Token.EOF) : program.substring(loc, loc + amount);
    }

    private char consume() {
        return loc == program.length() ? Token.EOF : program.charAt(loc++);
    }

    private String consumeLots(int amount) {
        return loc + amount > program.length() ? String.valueOf(Token.EOF) : program.substring(loc, loc += amount);
    }
    
    public void reset() { loc = 0; }
    public String toString() { return program; }
    private boolean numeric(char c) { return (c >= '0' && c <= '9'); }
    private boolean alpha(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); }
    private boolean space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\b'; }
}
