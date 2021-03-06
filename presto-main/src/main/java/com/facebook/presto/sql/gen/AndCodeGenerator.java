/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.gen;

import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.relational.RowExpression;
import com.google.common.base.Preconditions;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;
import io.airlift.bytecode.instruction.LabelNode;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.sql.gen.BytecodeGenerator.generateWrite;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantFalse;

public class AndCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(Signature signature, BytecodeGeneratorContext generator, Type returnType, List<RowExpression> arguments, Optional<Variable> outputBlockVariable)
    {
        Preconditions.checkArgument(arguments.size() == 2);

        Variable wasNull = generator.wasNull();
        BytecodeBlock block = new BytecodeBlock()
                .comment("AND")
                .setDescription("AND");

        BytecodeNode left = generator.generate(arguments.get(0), Optional.empty());
        BytecodeNode right = generator.generate(arguments.get(1), Optional.empty());

        block.append(left);

        IfStatement ifLeftIsNull = new IfStatement("if left wasNull...")
                .condition(wasNull);

        LabelNode end = new LabelNode("end");
        ifLeftIsNull.ifTrue()
                .comment("clear the null flag, pop left value off stack, and push left null flag on the stack (true)")
                .append(wasNull.set(constantFalse()))
                .pop(arguments.get(0).getType().getJavaType()) // discard left value
                .push(true);

        LabelNode leftIsTrue = new LabelNode("leftIsTrue");
        ifLeftIsNull.ifFalse()
                .comment("if left is false, push false, and goto end")
                .ifTrueGoto(leftIsTrue)
                .push(false)
                .gotoLabel(end)
                .comment("left was true; push left null flag on the stack (false)")
                .visitLabel(leftIsTrue)
                .push(false);

        block.append(ifLeftIsNull);

        // At this point we know the left expression was either NULL or TRUE.  The stack contains a single boolean
        // value for this expression which indicates if the left value was NULL.

        // eval right!
        block.append(right);

        IfStatement ifRightIsNull = new IfStatement("if right wasNull...");
        ifRightIsNull.condition()
                .append(wasNull);

        // this leaves a single boolean on the stack which is ignored since the value in NULL
        ifRightIsNull.ifTrue()
                .comment("right was null, pop the right value off the stack; wasNull flag remains set to TRUE")
                .pop(arguments.get(1).getType().getJavaType());

        LabelNode rightIsTrue = new LabelNode("rightIsTrue");
        ifRightIsNull.ifFalse()
                .comment("if right is false, pop left null flag off stack, push false and goto end")
                .ifTrueGoto(rightIsTrue)
                .pop(boolean.class)
                .push(false)
                .gotoLabel(end)
                .comment("right was true; store left null flag (on stack) in wasNull variable, and push true")
                .visitLabel(rightIsTrue)
                .putVariable(wasNull)
                .push(true);

        block.append(ifRightIsNull)
                .visitLabel(end);

        outputBlockVariable.ifPresent(output -> block.append(generateWrite(generator, returnType, output)));
        return block;
    }
}
