package sajti.scrollpicker;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.databinding.BindingAdapter;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.NestedScrollView;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static android.view.Gravity.CENTER;

/*
 * Made by Tamás Sajti  tamas.sajti.dev@gmail.com
 * To have a customizable and data-bindable NumberPicker.
 *
 * project home:                          https://github.com/tomeeeS/ScrollPicker
 * demo project showcasing functionality: https://github.com/tomeeeS/ScrollPickerDemo
 *
 * Notes:
 * - The items can't be edited like in NumberPicker.
 * - When you set a String list for items, the value is always just the index and not a custom interval of numbers set with setMin, setMax as in NumberPicker,
 *      but I don't think that's a real way of dealing with that obscure use case anyway, it's not robust for one, as you have to make sure that
 *      "The length of the displayed values array must be equal to the range of selectable numbers which is equal to getMaxValue() - getMinValue() + 1"
 *      why would you need to worry about making 3 calls right instead of just dealing with an offset on the indices at one place in the logic?
 *
 * Glossary:
 * value    - consistent with NumberPicker, if the list we set was such that its items are of String, then the value corresponds to the index of the selected item in the list,
 *              while in case of Integers it is the item's int value.
 * selector - the visual indication about the currently selected item at the middle of the view
 *
 * Licence: Apache-2.0 (do with it whatever you please)
 *
 * Implementation advices: (for those who might want to make changes)
 * - Do Not try to use setOnScrollChangeListener for listening for scroll stop. Unfortunately it isn't possible, android doesn't give any callbacks for that
 *      and we can't determine it from setOnScrollChange because there is no sensible threshold for scroll value change (y - oldY) that would be low enough to
 *      detect this event. There is no call to happen when oldY equals y - that would tell us clearly that the scrolling has stopped -
 *      and sometimes the last change is as much as 6 pixels so there is also no sensible threshold value.
 *      This scrollerTask solution is the best I could come up with. (check for slowing scroll from time to time and when it's in a threshold value we stop the
 *      current scrolling and start the correction scroll).
 *
 */

/**
 * Customizable and data-bindable NumberPicker-like UI element.
 */
public class ScrollPicker extends LinearLayout {

    private static final int LAYOUT = R.layout.scroll_picker;

    private static final int SHOWN_ITEM_COUNT_DEFAULT = 3;
    private static final boolean IS_SET_NEXT_OR_PREVIOUS_ITEM_ENABLED = true;
    private static final int SCROLL_STOP_CHECK_INTERVAL_MS = 20;
    private static final int TEXT_SIZE_DEFAULT = 18;
    private static final int SCROLL_INTO_PLACE_DURATION_MS_DEFAULT = 120;
    private static final int SELECTOR_STYLE_DEFAULT_INDEX = 0;
    public static final int SELECTOR_STROKE_WIDTH = 4;
    private static int SELECTOR_COLOR_DEFAULT;
    private static int TEXT_COLOR_DISABLED;
    private static int TEXT_COLOR_DEFAULT;
    private final float TOUCH_SLOP = ViewConfiguration.get( getContext() ).getScaledTouchSlop();

    protected ArrayList items; // the String or Integer items that we display
    private Rect selectPreviousItemRect; // the touch area rectangle for the select previous item functionality
    private Rect selectNextItemRect; // the touch area rectangle for the select next item functionality
    private ListItemType listItemType; // String or Int
    private NestedScrollView scrollView; // the parent view in which we have the elements in a vertical LinearLayout. Good for scrolling.
    private Context context;
    int shownItemCount = SHOWN_ITEM_COUNT_DEFAULT; // how many items can be shown at a time
    private int spaceCellCount; // how many cells equate to the height of the space before (and after) the text views
    int cellHeight; // (approximate) height of one item
    private List< OnValueChangeListener > onValueChangeListeners = new LinkedList<>();
    private Paint selectorPaint;
    private Rect selectorRect;
    private float mStartY;
    private boolean isExternalValueChange = false;
    private boolean isOnSizeChangedFinished = false;
    private boolean isListInited = false;
    private int selectedIndex = 0;
    private Runnable scrollerTask;
    private int lastScrollY;
    private AtomicInteger scrollYTo = new AtomicInteger();
    private float textSize;
    private int textColor, enabledTextColor;
    private boolean isEnabled;
    private Integer storedValue; // was there a value set yet and what was it
    private LinearLayout itemsLayout;
    private View correctionViewTop; // these are to take up the space which is left when total view height is not divisible by shownItemCount
    private View correctionViewBottom;
    private SelectorStyle selectorStyle;
    private int selectorRectInset;

    // region public interface

    public ScrollPicker( Context context ) {
        this( context, null );
    }

    public ScrollPicker( Context context, AttributeSet attrs ) {
        this( context, attrs, 0 );
    }

    public ScrollPicker( Context context, AttributeSet attrs, int defStyle ) {
        super( context, attrs, defStyle );
        this.context = context;
        setWillNotDraw( false );
        init();
        initValues( attrs );
    }

    /**
     * Gets the selected item. Can be data-bound (2-way).
     *
     * @return If the list we set was such that its items are of String, then the returned value corresponds to the index of the selected item in the list,
     *         while in case of Integers it is the selected item's int value.
     */
    public int getValue() {
        return getValueForIndex( selectedIndex );
    }

    /**
     * Sets the selected item. Can be data-bound (2-way).
     *
     * @param value If the list we set was such that its items are of String, then the value corresponds to the indiex of the selected item in the list,
     *              while in case of Integers it is the item's int value.
     */
    public void setValue( int value ) {
        if( isListInited ) {
            if( value != getValueForIndex( selectedIndex ) ) {
                isExternalValueChange = true; // external setValue, no need to trigger value changed callback
                selectItemFromIndex( value );
                if( isInited() ) {
                    scrollYTo( selectedIndex * cellHeight );
                    invalidate();
                }
                isExternalValueChange = false;
            }
        } else
            storedValue = value;
    }

    private void selectItemFromIndex( int index ) {
        switch( listItemType ) {
            case INT:
                selectItem( getIndexOfValue( index ) );
                break;
            case STRING:
                selectItem( index );
                break;
        }
    }

    /**
     * Returns if the view is enabled or not i.e. able to receive and process touch events.
     *  <p>
     *  Note: If you disable it it will still work with data-bound changes of your view model or programmatical calls.
     *  </p>
     */
    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Data binding helper method for {@link #setEnabled(boolean)}.
     */
    @BindingAdapter("isEnabled")
    public static void setEnabled( ScrollPicker scrollPicker, boolean isEnabled ) {
        scrollPicker.setEnabled( isEnabled );
    }

    /**
     *  Should the view be enabled or not i.e. to receive and process touch events. Can be data-bound.
     *  <p>
     *  Note: If you disable it it will still work with data-bound changes of your view model or programmatical calls.
     *  </p>
     */
    public void setEnabled( boolean isEnabled ) {
        if( this.isEnabled != isEnabled ) {
            this.isEnabled = isEnabled;
            if( isEnabled )
                setEnabledTextColor( enabledTextColor );
            else
                setDisabledTextColor( TEXT_COLOR_DISABLED );
            if( isInited() )
                initScrollView();
        }
    }

    /**
     * Sets the selector's color. The selector is the visual indication about the currently selected item at the middle of the view.
     *
     * @param selectorColor Standard android int representation of a color.
     */
    public void setSelectorColor( int selectorColor ) {
        selectorPaint.setColor( selectorColor );
        initScrollView();
    }

    /**
     * Returns the text size of the list items displayed.
     */
    public float getTextSize() {
        return textSize;
    }

    /**
     * Sets the text size of the list items displayed.
     */
    public void setTextSize( float textSize ) {
        this.textSize = textSize;
        initScrollView();
    }

    /**
     * Data binding helper method for {@link #setList(ArrayList)}.
     */
    public void setList( final ObservableField< ArrayList > items ) {
        setList( items.get() );
        items.addOnPropertyChangedCallback( new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged( Observable sender, int propertyId ) {
                setList( items.get() );
            }
        } );
    }

    /**
     * Sets the list whose items this view displays. Can be data-bound. //todo
     *
     * @param items An ArrayList whose template type must be either String or Integer. Must be non-empty.
     *              In case of ArrayList&lt;String&gt the value that you can set to this view with {@link #setValue(int)} will correspond to the index of the selected item in this list,
     *              while in case of ArrayList&lt;Integer&gt it will be the item's int value.
     */
    public void setList( ArrayList items ) {
        if( isListInited ) // if we already had a list set, we set the first item as default
            setValue( getValueForIndex( 0 ) );
        setListItemType( items );
        this.items = new ArrayList( items );
        isListInited = true;
        initScrollView();
        if( storedValue != null ) {
            setValue( storedValue );
            storedValue = null;
        }
    }

    private void setListItemType( ArrayList items ) {
        if( items.get( 0 ) instanceof String )
            this.listItemType = ListItemType.STRING;
        else if( items.get( 0 ) instanceof Integer )
            this.listItemType = ListItemType.INT;
        else {
            Log.e( "ScrollPicker", "items template type must be either String or Integer!" );
            this.listItemType = ListItemType.INT;
        }
    }

    /**
     * Sets how many items can be shown at a time
     */
    public void setShownItemCount( int shownItemCount ) {
        this.shownItemCount = shownItemCount;
        spaceCellCount = shownItemCount / 2;
        initSelectorAndCellHeight();
        initScrollView();
    }

    /**
     * Sets the text color of all the items in the list.
     *
     * @param textColor Standard android int representation of a color.
     */
    public void setTextColor( int textColor ) {
        setEnabledTextColor( textColor );
        initScrollView();
    }

    /**
     * Adds a listener for the value change event, which happens when a different item gets selected with touch.
     * This callback does not happen when the setValue is called programmatically.
     */
    public void addOnValueChangedListener( OnValueChangeListener onValueChangeListener ) {
        onValueChangeListeners.add( onValueChangeListener );
    }

    /**
     * Sets the selector display style.
     */
    public void setSelectorStyle( SelectorStyle selectorStyle ) {
        if( this.selectorStyle == null || this.selectorStyle != selectorStyle ) {
            this.selectorStyle = selectorStyle;
            switch( selectorStyle ) {
                case RECTANGLE_FILLED:
                    selectorRectInset = SELECTOR_STROKE_WIDTH / 2;
                    selectorPaint.setStyle( Paint.Style.FILL_AND_STROKE );
                    break;
                case RECTANGLE:
                    selectorRectInset = SELECTOR_STROKE_WIDTH / 2;
                    selectorPaint.setStyle( Paint.Style.STROKE );
                    break;
                case CLASSIC:
                    selectorPaint.setStyle( Paint.Style.STROKE );
                    selectorRectInset = -SELECTOR_STROKE_WIDTH / 2;
                    break;
//                case CLASSIC_SHORT: // same as classic but with only the middle third of width?
//                    break;
            }
            setSelectorRect();
            invalidate();
        }
    }

    /**
     * Removes a listener for the value change event, which happens when a different item gets selected with touch.
     * Wouldn't be a problem if you tried to remove one which you haven't actually added previously.
     */
    public void removeOnValueChangedListener( OnValueChangeListener onValueChangeListener ) {
        onValueChangeListeners.remove( onValueChangeListener );
    }

    // endregion public interface

    // select previous or next on touching above or below the selection area
    @Override
    public boolean onTouchEvent( MotionEvent event ) {
        if( !isEnabled )
            return true;
        if( IS_SET_NEXT_OR_PREVIOUS_ITEM_ENABLED ) {
            float x = event.getX();
            float y = event.getY();
            if( selectPreviousItemRect.contains( (int)x, (int)y ) ) {
                selectPreviousItem();
            }
            if( selectNextItemRect.contains( (int)x, (int)y ) ) {
                selectNextItem();
            }
            invalidate();
        }
        return false;
    }

    @Override
    protected void onSizeChanged( int w, int h, int oldw, int oldh ) {
        super.onSizeChanged( w, h, oldw, oldh );
        isOnSizeChangedFinished = true;
        if( w > 0 )
            initSelectorAndCellHeight();
    }

    @Override
    public boolean dispatchTouchEvent( MotionEvent event ) {
        if( !isEnabled )
            return true;
        switch( event.getAction() ) {
            case MotionEvent.ACTION_DOWN:
                mStartY = event.getY();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                float y = event.getY();
                float yDeltaTotal = y - mStartY;
                if( Math.abs( yDeltaTotal ) < TOUCH_SLOP ) { // we aren't scrolling
                    return onTouchEvent( event );
                } else {
                    lastScrollY = scrollView.getScrollY();
                    restartScrollStopCheck();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                break;
        }
        return super.dispatchTouchEvent( event );
    }

    @Override
    protected void dispatchDraw( Canvas canvas ) {
        // whatever is before the super call will be drawn to the background, so now the selector is drawn behind the list, so the selected item's text is visible too
        canvas.drawRect( selectorRect, selectorPaint );
        super.dispatchDraw( canvas );
    }
    // for testing

    int getListScrollY() {
        return scrollView.getScrollY();
    }

    private int getIndexOfValue( int value ) {
        return getIntItems().indexOf( value );
    }

    private void restartScrollStopCheck() {
        postDelayed( scrollerTask, SCROLL_STOP_CHECK_INTERVAL_MS );
    }

    private void initValues( AttributeSet attrs ) {
        TEXT_COLOR_DISABLED = ContextCompat.getColor( context, R.color.textColorDisabled );
        TEXT_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.textColorDefault );
        SELECTOR_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.selectorColorDefault );

        isEnabled = true;
        TypedArray attributesArray = context.obtainStyledAttributes( attrs, R.styleable.ScrollPicker );

        setSelectorColor( attributesArray.getColor( R.styleable.ScrollPicker_selectorColor, SELECTOR_COLOR_DEFAULT ) );
        setSelectorStyle( SelectorStyle.values()[ attributesArray.getInt( R.styleable.ScrollPicker_selectorStyle, SELECTOR_STYLE_DEFAULT_INDEX ) ] );
        setShownItemCount( attributesArray.getInt( R.styleable.ScrollPicker_itemsToShow, SHOWN_ITEM_COUNT_DEFAULT ) );

        textSize = attributesArray.getFloat( R.styleable.ScrollPicker_textSize, TEXT_SIZE_DEFAULT );
        int textColor = attributesArray.getInt( R.styleable.ScrollPicker_textColor, TEXT_COLOR_DEFAULT );
        setEnabledTextColor( textColor );
        setEnabled( attributesArray.getBoolean( R.styleable.ScrollPicker_isEnabled, true ) );

        attributesArray.recycle();
    }

    private void setEnabledTextColor( int textColor ) {
        if( isEnabled )
            this.textColor = textColor;
        enabledTextColor = textColor;
    }

    private void setDisabledTextColor( int textColorDisabled ) {
        textColor = textColorDisabled;
    }

    private void scrollYTo( int scrollYTo ) {
        ObjectAnimator scrollYAnimator = ObjectAnimator.ofInt( scrollView, "scrollY", scrollYTo ).
                setDuration( SCROLL_INTO_PLACE_DURATION_MS_DEFAULT );
        scrollYAnimator.setInterpolator( new LinearInterpolator() );
        scrollYAnimator.start();
    }

    private void init() {
        LayoutInflater inflater = (LayoutInflater)context.getSystemService( Context.LAYOUT_INFLATER_SERVICE );
        inflater.inflate( LAYOUT, this, true );
        selectorPaint = new Paint();
        selectorPaint.setStrokeWidth( SELECTOR_STROKE_WIDTH );

        scrollerTask = new Runnable() {
            @Override
            public void run() {
                int newPosition = scrollView.getScrollY();
                if( lastScrollY == newPosition ) { // has probably stopped. we can't be sure unfortunately and this is the best you can do with the lacking android api.
                    scrollView.fling( 0 ); // we stop the scrolling to be sure. better than smoothScrollTo( 0, 0 ): it jumps once and back fast while stopping and looks bad
                    scrollYTo.set( lastScrollY );
                    ScrollPicker.this.selectNearestItemOnScrollStop();
                } else {
                    lastScrollY = scrollView.getScrollY();
                    ScrollPicker.this.restartScrollStopCheck();
                }
            }
        };
        scrollView = findViewById( R.id.scrollView );
        correctionViewTop = findViewById( R.id.correctionViewTop );
        correctionViewBottom = findViewById( R.id.correctionViewBottom );
    }

    // corrections are necessary at the end of scrolling to set ourself to a valid position
    private void selectNearestItemOnScrollStop() {
        int firstVisibleItemIndex = getFirstVisibleItemIndex();
        Rect firstVisibleRect = getFirstVisibleRect( firstVisibleItemIndex );

        // which item should be selected? the item above or below the selection area?
        // we know by checking how much height of the view of firstVisibleItemIndex is visible
        int visibleHeightOfItem = ( firstVisibleRect.height() > cellHeight ) ?
                firstVisibleRect.height() % cellHeight : // % cellHeight: the "space" view's (the one at the start) height is multiple of cellHeight
                firstVisibleRect.height();
        int scrollYby = getScrollYby( visibleHeightOfItem ); // how much to scroll the scrollView
        scrollYBy( scrollYby );
    }

    private int getScrollYby( int visibleHeightOfItem ) {
        int scrollYby;
        if( Math.abs( visibleHeightOfItem ) <= cellHeight / 2 ) {
            scrollYby = visibleHeightOfItem;
        } else {
            scrollYby = visibleHeightOfItem - cellHeight;
        }
        return scrollYby;
    }

    @NonNull
    private Rect getFirstVisibleRect( int firstVisibleItemIndex ) {
        View child = itemsLayout.getChildAt( firstVisibleItemIndex );
        Rect firstVisibleRect = new Rect( 0, 0, child.getWidth(), child.getHeight() );
        itemsLayout.getChildVisibleRect( child, firstVisibleRect, null );
        return firstVisibleRect;
    }

    private int getFirstVisibleItemIndex() {
        int cellCount = scrollView.getScrollY() / cellHeight;
        int spaceHeight = spaceCellCount * cellHeight;
        int firstVisibleItemIndex;
        if( scrollView.getScrollY() > spaceHeight )
            firstVisibleItemIndex = cellCount - ( spaceCellCount - 1 );
        else
            firstVisibleItemIndex = 0;
        return firstVisibleItemIndex;
    }

    private void initSelectorAndCellHeight() {
        cellHeight = (int)Math.round( (double)getHeight() / (double)shownItemCount );
        if( cellHeight > 0 ) {
            setSelectorRect();
            selectPreviousItemRect = new Rect( 0,
                    0,
                    getWidth(),
                    cellHeight * spaceCellCount );
            selectNextItemRect = new Rect( 0,
                    selectorRect.bottom,
                    getWidth(),
                    getHeight() );
            post( new Runnable() {
                @Override
                public void run() {
                    ScrollPicker.this.initScrollView();
                }
            } );
        }
    }

    private void setSelectorRect() {
        int cellHeightCeiling = (int)Math.ceil( (double)getHeight() / (double)shownItemCount );
        selectorRect = new Rect( selectorRectInset,
                cellHeightCeiling * spaceCellCount,
                getWidth() - selectorRectInset,
                cellHeightCeiling * ( spaceCellCount + 1 ) );
    }

    private void initScrollView() {
        if( isInited() ) {
            scrollView.removeAllViews();
            int scrollViewHeight = cellHeight * shownItemCount;
            setViewHeight( scrollView, scrollViewHeight );
            setCorrectionViewsHeights( scrollViewHeight );
            itemsLayout = new LinearLayout( getContext() );
            itemsLayout.setOrientation( LinearLayout.VERTICAL );

            int spaceHeight = cellHeight * spaceCellCount;
            itemsLayout.addView( getSpace( spaceHeight ) );
            for( int i = 0; i < items.size(); ++i )
                itemsLayout.addView( getTextView( i ) );
            if( shownItemCount % 2 == 0 )
                spaceHeight -= cellHeight;
            itemsLayout.addView( getSpace( spaceHeight ) );

            scrollView.addView( itemsLayout );
            itemsLayout.getViewTreeObserver().addOnPreDrawListener( new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() { // we scroll to the selected item before presenting ourselves. this is only done at initialization
                    scrollView.getViewTreeObserver().removeOnPreDrawListener( this );
                    int scrollYTo = selectedIndex * cellHeight;
                    scrollView.scrollTo( 0, scrollYTo );
                    ScrollPicker.this.scrollYTo.set( scrollYTo );
                    return false;
                }
            } );
        }
    }

    private void setCorrectionViewsHeights( int scrollViewHeight ) {
        setViewHeight( correctionViewTop, calculateViewHeight( false, scrollViewHeight ) );
        setViewHeight( correctionViewBottom, calculateViewHeight( true, scrollViewHeight ) );
    }

    private int calculateViewHeight( boolean isBottom, int scrollViewHeight ) {
        int heightHalf = ( getHeight() - scrollViewHeight ) / 2;
        int heightMod = ( getHeight() - scrollViewHeight ) % 2;
        return heightHalf + (isBottom ? heightMod : 0 );
    }

    private View getSpace( int height ) {
        View space = new View( getContext() );
        space.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT ) );
        setViewHeight( space, height );
        return space;
    }

    private void setViewHeight( View space, int height ) {
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)space.getLayoutParams();
        p.height = height;
        space.setLayoutParams( p );
    }

    private boolean isInited() {
        return isOnSizeChangedFinished && isListInited;
    }

    @NonNull
    private TextView getTextView( int itemIndex ) {
        TextView textView = new TextView( getContext() );
        switch( listItemType ) {
            case INT:
                textView.setText( "" + getIntItems().get( itemIndex ) );
                break;
            case STRING:
                textView.setText( getStringItems().get( itemIndex ) );
                break;
        }
        textView.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT ) );
        textView.setTextSize( TypedValue.COMPLEX_UNIT_SP, textSize );
        textView.setTextColor( textColor );
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)textView.getLayoutParams();
        p.height = cellHeight;
        textView.setLayoutParams( p );
        textView.setGravity( CENTER );
        return textView;
    }

    private ArrayList< String > getStringItems() {
        return (ArrayList< String >)items;
    }

    private void selectNextItem() {
        if( selectedIndex < items.size() - 1 ) {
            scrollYBy( cellHeight );
        }
    }

    private void selectPreviousItem() {
        if( selectedIndex > 0 ) {
            scrollYBy( -cellHeight );
        }
    }

    private void scrollYBy( int scrollYby ) {
        scrollYTo.set( scrollYTo.get() + scrollYby );
        scrollYTo( scrollYTo.get() );
        selectItem( scrollYTo.get() / cellHeight );
    }

    private void selectItem( int newIndex ) {
        if( !isExternalValueChange && selectedIndex != newIndex ) {
            selectedIndex = newIndex;
            for( OnValueChangeListener l : onValueChangeListeners )
                sendOnValueChanged( newIndex, l );
        } else
            selectedIndex = newIndex;
        scrollYTo.set( newIndex * cellHeight );
    }

    // if we use the Int implementation, send the Value itself, otherwise send the index of the selected String
    private void sendOnValueChanged( int newIndex, OnValueChangeListener l ) {
        l.onValueChange( getValueForIndex( newIndex ) );
    }

    private int getValueForIndex( int index ) {
        return listItemType == ListItemType.STRING ?
                index :
                getIntItems().get( index );
    }

    private ArrayList< Integer > getIntItems() {
        return (ArrayList< Integer >)items;
    }

    public enum ListItemType {
        INT, STRING
    }

    public interface OnValueChangeListener {
        void onValueChange( int newValue ); // if we use the Int implementation, send the value itself, otherwise send the index of the selected String
    }

}
