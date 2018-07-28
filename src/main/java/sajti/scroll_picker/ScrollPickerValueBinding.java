package sajti.scroll_picker;

import android.databinding.BindingAdapter;
import android.databinding.InverseBindingAdapter;
import android.databinding.InverseBindingListener;

// binding to the 'value' of the ScrollPicker, which is the selected item's index in case of ListItemType.STRING, and
// the selected int item in case of ListItemType.INT
public class ScrollPickerValueBinding {

    @BindingAdapter( value = "valueAttrChanged" )
    public static void setListener( ScrollPicker scrollPicker, final InverseBindingListener listener ) {
        if( listener != null ) {
            scrollPicker.addOnValueChangedListener( newValue -> listener.onChange() );
        }
    }

    @InverseBindingAdapter( attribute = "value" )
    public static Integer getValue( ScrollPicker scrollPicker ) {
        return scrollPicker.getValue();
    }

}
