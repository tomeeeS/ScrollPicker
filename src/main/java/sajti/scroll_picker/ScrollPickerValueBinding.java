package sajti.scroll_picker;

import android.databinding.BindingAdapter;
import android.databinding.InverseBindingAdapter;
import android.databinding.InverseBindingListener;

public class ScrollPickerValueBinding {

    private static Integer lastGetValue; // it's for avoiding instant resetting of selectedIndex by bindingAdapter setValue in ScrollPickerValueBinding

    @BindingAdapter( value = "valueAttrChanged" )
    public static void setListener( ScrollPicker scrollPicker, final InverseBindingListener listener ) {
        if( listener == null ) {
            scrollPicker.addOnValueChangedListener( null );
        } else {
            scrollPicker.addOnValueChangedListener( new ScrollPicker.OnValueChangeListener() {
                @Override
                public void onValueChange( int newValue ) {
                    listener.onChange();
                }
            } );
        }
    }

    @BindingAdapter( "value" )
    public static void setValue( ScrollPicker scrollPicker, int value ) {
        if( lastGetValue == null || value != lastGetValue )
            scrollPicker.setValue( value );
    }

    @InverseBindingAdapter( attribute = "value" )
    public static int getValue( ScrollPicker scrollPicker ) {
        lastGetValue = scrollPicker.getValue();
        return lastGetValue;
    }
}
