package jbse.algo.meta;

import java.util.function.Supplier;

import jbse.algo.Algorithm;
import jbse.algo.StrategyUpdate;
import jbse.tree.DecisionAlternative_NONE;

/**
 * An {@link Algorithm} implementing the effect of a method call
 * which pushes {@code true} on the operand stack.
 * 
 * @author Pietro Braione
 *
 */
public final class Algo_JBSE_ANALYSIS_ISRUNBYJBSE extends Algo_INVOKEMETA {
    public Algo_JBSE_ANALYSIS_ISRUNBYJBSE() {
        super(false);
    }
    
    @Override
    protected Supplier<Integer> numOperands() {
        return () -> 0;
    }
    
    @Override
    protected StrategyUpdate<DecisionAlternative_NONE> updater() {
        return (state, alt) -> {
            state.pushOperand(state.getCalculator().valInt(1)); //means true
        };
    }
}
