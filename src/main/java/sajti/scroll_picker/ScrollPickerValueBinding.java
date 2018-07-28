package sajti.scroll_picker;

import android.databinding.BindingAdapter;
import android.databinding.InverseBindingAdapter;
import android.databinding.InverseBindingListener;

public class ScrollPickerValueBinding {

    private static Integer lastGetValue; // it's for avoiding instant resetting of selectedIndex by bindingAdapter setValue in ScrollPickerValueBinding

    @BindingAdapter( value = "valueAttrChanged" )
    public static void setListener( ScrollPicker scrollPicker, final InverseBindingListener listener ) {
        if( listener != null ) {
            scrollPicker.addOnValueChangedListener( newValue -> listener.onChange() );
        }
    }

    @BindingAdapter( "value" )
    public static void setValue( ScrollPicker scrollPicker, Integer value ) {
        if( lastGetValue == null || !value.equals( lastGetValue ) )
            scrollPicker.setValue( value );
    }

    @InverseBindingAdapter( attribute = "value" )
    public static Integer getValue( ScrollPicker scrollPicker ) {
        lastGetValue = scrollPicker.getValue();
        return lastGetValue;
    }

}
