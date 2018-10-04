package com.github.tomeees.scrollpicker;


/**
 * Thrown when an invalid index is being set, or a value that isn't in the items.
 */
class WrongValueException extends RuntimeException {

    public WrongValueException( String message ) {
        super( message );
    }
}
