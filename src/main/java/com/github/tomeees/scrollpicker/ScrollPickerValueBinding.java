package com.github.tomeees.scrollpicker;

import androidx.databinding.BindingAdapter;
import androidx.databinding.InverseBindingAdapter;
import androidx.databinding.InverseBindingListener;

// binding to the 'value' of the ScrollPicker, which is the selected item's index in case of ListItemType.OTHER, and
// the selected int item in case of ListItemType.INT
public class ScrollPickerValueBinding {

    @BindingAdapter( value = "valueAttrChanged" )
    public static void setListener( ScrollPicker scrollPicker, final InverseBindingListener listener ) {
        if( listener != null ) {
            scrollPicker.addOnValueChangedListener( new ScrollPicker.OnValueChangeListener() {
                @Override
                public void onValueChange( int newValue ) {
                    listener.onChange();
                }
            } );
        }
    }

    @InverseBindingAdapter( attribute = "value" )
    public static Integer getValue( ScrollPicker scrollPicker ) {
        return scrollPicker.getValue();
    }

}
