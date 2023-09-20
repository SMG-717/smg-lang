Praser syntax:

Program     -> [Statement]*
Statement   -> let [Variable] = [Expression] | [Variable] = [Expression] | [Expression]
Expression  -> [Term] | [Term] [Operator] [Term]         
Term        -> ([Term]) | [Literal] | [Variable]
Literal     -> [BooleanLiteral] | [StringLiteral] | [NumberLiteral] | [DateLiteral]
Operator    ->  '^'                                 prec = 8
                '*'   | '/'   | '%'                 prec = 7
                '+'   | '-'                         prec = 6
                '<<'  | '>>'                        prec = 5
                '>'   | '<'   | '>='  | '<='        prec = 4
                '=='  | '!='                        prec = 3
                '&'   | '|'   | 'xor'               prec = 2
                'and' | 'or'                        prec = 1


features to add:
- else if
- standalone scopes