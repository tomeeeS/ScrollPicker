package sajti.scrollpicker;

/**
 * Display style possibilities for the {@link ScrollPicker}'s selector.
 * <p> {@link SelectorStyle#RECTANGLE_FILLED} </p>
 * <p> {@link SelectorStyle#RECTANGLE} </p>
 * <p> {@link SelectorStyle#CLASSIC} </p>
 */
public enum SelectorStyle {
    /**
     * Displays a colored filled rectangle behind the selected item.
     */
    RECTANGLE_FILLED,

    /**
     * Displays a colored rectangle around the selected item.
     */
    RECTANGLE,

    /**
     * Displays two colored lines above and below the selected item like in {@link android.widget.NumberPicker}.
     */
    CLASSIC,

//    /**
//     * Displays two colored lines above and below the selected item that go only at the middle third of the ScrollPicker's width.
//     */
//    CLASSIC_SHORT
}
