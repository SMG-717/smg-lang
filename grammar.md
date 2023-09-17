Praser syntax:

Program     -> [Statement]*
Statement   -> let [Variable] = [Expression] | [Expression]
Expression  -> [Term] | [Term] [Operator] [Term]         
Term        -> ([Term]) | [Literal] | [Variable]
Literal     -> [BooleanLiteral] | [StringLiteral] | [NumberLiteral] | [DateLiteral]
Operator    ->  '^'                                 prec = 6
                '*'   | '/'   | '%'                 prec = 5
                '+'   | '-'                         prec = 4
                '>'   | '<'   | '>='  | '<='        prec = 3
                '=='  | '!='                        prec = 2
                'and' | 'or'                        prec = 1
