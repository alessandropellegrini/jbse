package jbse.algo.exc;

import jbse.algo.Algorithm;

/**
 * Exception raised whenever an algorithm requires 
 * the engine to execute another algorithm before
 * executing the next bytecode.
 * 
 * @author Pietro Braione
 *
 */
public final class ContinuationException extends Exception {
    /**
     * 
     */
    private static final long serialVersionUID = 1986437767183635494L;
    
    private final Algorithm<?, ?, ?, ?, ?> continuation;

    public ContinuationException(Algorithm<?, ?, ?, ?, ?> continuation) {
        this.continuation = continuation;
    }

    public Algorithm<?, ?, ?, ?, ?> getContinuation() {
        return this.continuation;
    }
}
