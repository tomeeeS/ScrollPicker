package sajti.scroll_picker;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.databinding.BindingAdapter;
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
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static android.view.Gravity.CENTER;

/**
 * Made by Tamás Sajti,  tomeeeS@github
 * To have a customizable and data-bindable NumberPicker.
 *
 * Glossary:
 * value - consistent with NumberPicker, if the list we set was such that its items are of String, then the value corresponds to the indice of the selected items in the list,
 *              while in case of Integers it is the items' int value.
 * selector - the visual indication about the currently selected item at the middle of the view
 *
 * Implementation advices: (for those who would want to refactor this)
 * - Do Not try to use setOnScrollChangeListener for listening for scroll stop. Unfortunately it isn't possible, android doesn't give any callbacks for that
 *      and we can't determine it from setOnScrollChange because there is no sensible threshold for y axis scroll value change (y - oldY) that would be low enough to
 *      detect this event. There is no call to happen when oldY equals y - that would tell us clearly that the scrolling has stopped -
 *      and sometimes the last change is as much as 6 pixels.
 *      This scrollerTask solution is the best I could come up with. (check for slowing scroll from time to time and when it's in a threshold value we stop the
 *      current scrolling and start the correction scroll).
 */
public class ScrollPicker extends LinearLayout {

    public static final int LAYOUT = R.layout.scroll_picker;

    public static final int SHOWN_ITEM_COUNT_DEFAULT = 3;
    public static final boolean IS_SET_NEXT_OR_PREVIOUS_ITEM_ENABLED = true;
    public static final int SCROLL_STOP_CHECK_INTERVAL_MS = 20;
    public static final int POSITIVE_SCROLL_CORRECTION = 1;
    public static final int TEXT_SIZE_DEFAULT = 18;
    public static final int SELECTOR_HEIGHT_CORRECTION = 1;
    public static final int SCROLL_INTO_PLACE_DURATION_MS_DEFAULT = 120;
    public static int SELECTOR_COLOR_DEFAULT;
    public static int TEXT_COLOR_DISABLED;
    public static int TEXT_COLOR_DEFAULT;
    private final float TOUCH_SLOP = ViewConfiguration.get( getContext() ).getScaledTouchSlop();

    protected ArrayList items; // the String or Integer items that we display
    private Rect selectPreviousItemRect; // the touch area rectangle for the select previous item functionality
    private Rect selectNextItemRect; // the touch area rectangle for the select next item functionality
    private ListItemType listItemType; // String or Int
    private NestedScrollView scrollView; // the parent view in which we have the elements in a vertical LinearLayout. Good for scrolling.
    private Context context;
    private int itemsToShow = SHOWN_ITEM_COUNT_DEFAULT; // the maximum item count which will be displayed at a time
    private int spaceCellCount; // how many cells equate to the height of the space before (and after) the text views
    private int cellHeight; // height of one item
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
        return getValue( selectedIndex );
    }

    /**
     * Sets the selected item. Can be data-bound (2-way).
     *
     * @param value If the list we set was such that its items are of String, then the value corresponds to the indice of the selected items in the list,
     *              while in case of Integers it is the items' int value.
     */
    public void setValue( int value ) {
        if( value != selectedIndex ) {
            isExternalValueChange = true; // external setValue, no need to trigger value changed callback
            switch( listItemType ) {
                case INT:
                    selectItem( value - ( getIntItems() ).get( 0 ) );
                    break;
                case STRING:
                    selectItem( value );
                    break;
            }
            if( isInited() ) {
                scrollYTo( selectedIndex * cellHeight );
                invalidate();
            }
            isExternalValueChange = false;
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
    }

    /**
     * Sets the text size of the list items displayed.
     */
    public void setTextSize( float textSize ) {
        this.textSize = textSize;
        initScrollView();
    }

    /**
     * Sets the list whose items this view displays. Can be data-bound. //todo
     *
     * @param items An ArrayList whose template type must be either String or Integer. Must be non-empty.
     *              In case of ArrayList&lt;String&gt the value that you can set to this view with {@link #setValue(int)} will correspond to the index of the selected item in this list,
     *              while in case of ArrayList&lt;Integer&gt it will be the item's int value.
     */
    public void setList( ArrayList items ) {
        isListInited = true;
        if( items.get( 0 ) instanceof String )
            this.listItemType = ListItemType.STRING;
        else if( items.get( 0 ) instanceof Integer )
            this.listItemType = ListItemType.INT;
        else {
            Log.e( "ScrollPicker", "items template type must be either String or Integer!" );
            this.listItemType = ListItemType.INT;
        }
        this.items = new ArrayList( items );
        initScrollView();
    }

    /**
     * Sets the maximum item count which will be displayed at a time. (Maximum because at the start and at the end of the list there is space to allow us to select the first or last
     * items respectively at the middle of the view)
     */
    public void setShownItemCount( int itemsToShow ) {
        this.itemsToShow = itemsToShow;
        spaceCellCount = itemsToShow / 2;
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
        switch( event.getAction() ) {
            case MotionEvent.ACTION_DOWN:
                if( !isEnabled )
                    return true;
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
        canvas.drawRect( selectorRect, selectorPaint ); // whatever is before the super call will be drawn to the background, so now the selector is drawn behind the list
        super.dispatchDraw( canvas );
    }

    private void restartScrollStopCheck() {
        postDelayed( scrollerTask, SCROLL_STOP_CHECK_INTERVAL_MS );
    }

    private void initValues( AttributeSet attrs ) {
        TEXT_COLOR_DISABLED = ContextCompat.getColor( context, R.color.textColorDisabled );
        TEXT_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.textColorDefault );
        SELECTOR_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.selectorColorDefault );

        TypedArray attributesArray = context.obtainStyledAttributes( attrs, R.styleable.ScrollPicker );

        setSelectorColor( attributesArray.getColor( R.styleable.ScrollPicker_selectorColor, SELECTOR_COLOR_DEFAULT ) );
        selectorPaint.setStyle( Paint.Style.FILL );
        setShownItemCount( attributesArray.getInt( R.styleable.ScrollPicker_itemsToShow, SHOWN_ITEM_COUNT_DEFAULT ) );

        textSize = attributesArray.getFloat( R.styleable.ScrollPicker_textSize, TEXT_SIZE_DEFAULT );
        int textColor = attributesArray.getInt( R.styleable.ScrollPicker_textColor, TEXT_COLOR_DEFAULT );
        setEnabledTextColor( textColor );
        isEnabled = true;
        setEnabled( attributesArray.getBoolean( R.styleable.ScrollPicker_isEnabled, true ) );

        attributesArray.recycle();
    }

    private void setEnabledTextColor( int textColor ) {
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

        scrollerTask = () -> {
            int newPosition = scrollView.getScrollY();
            if( lastScrollY == newPosition ) { // has probably stopped. we can't be sure unfortunately and this is the best you can do with the lacking android api.
                scrollView.fling( 0 ); // we stop the scrolling to be sure. better than smoothScrollTo( 0, 0 ): it jumps once and back fast while stopping and looks bad
                scrollYTo.set( lastScrollY );
                selectNearestItemOnScrollStop();
            } else {
                lastScrollY = scrollView.getScrollY();
                restartScrollStopCheck();
            }
        };
        scrollView = findViewById( R.id.scrollView );
    }

    // corrections are necessary at the end of scrolling to set ourself to a valid position
    private void selectNearestItemOnScrollStop() {
        LinearLayout verticalLayout = (LinearLayout)scrollView.getChildAt( 0 );
        int cellCount = scrollView.getScrollY() / cellHeight;
        int spaceHeight = spaceCellCount * cellHeight;
        int firstVisibleItemIndex;
        if( scrollView.getScrollY() > spaceHeight )
            firstVisibleItemIndex = cellCount - ( spaceCellCount - 1 );
        else
            firstVisibleItemIndex = 0;
        View child = verticalLayout.getChildAt( firstVisibleItemIndex );
        Rect rect = new Rect( 0, 0, child.getWidth(), child.getHeight() );
        verticalLayout.getChildVisibleRect( child, rect, null );

        // which item should be selected? the item above or below the selection area?
        // we know by checking how much height of the view of firstVisibleItemIndex is visible
        int visibleHeightOfItem = ( rect.height() > cellHeight ) ? rect.height() % cellHeight : rect.height(); // % cellHeight: the space view's height is multiple of cellHeight
        int scrollYby;
        if( Math.abs( visibleHeightOfItem ) <= cellHeight / 2 ) {
            scrollYby = visibleHeightOfItem + POSITIVE_SCROLL_CORRECTION;
        } else {
            scrollYby = visibleHeightOfItem - cellHeight;
        }
        scrollYBy( scrollYby );
    }

    private void initSelectorAndCellHeight() {
        cellHeight = (int)Math.round( (double)getHeight() / (double)itemsToShow );
        if( cellHeight > 0 ) {
            int cellHeightCeiling = (int)Math.ceil( (double)getHeight() / (double)itemsToShow );
            selectorRect = new Rect( 0,
                    cellHeightCeiling * spaceCellCount - SELECTOR_HEIGHT_CORRECTION,
                    getWidth(),
                    cellHeightCeiling * ( spaceCellCount + 1 ) );
            selectPreviousItemRect = new Rect( 0,
                    0,
                    getWidth(),
                    cellHeight * spaceCellCount );
            selectNextItemRect = new Rect( 0,
                    selectorRect.bottom,
                    getWidth(),
                    getHeight() );
            post( this::initScrollView );
        }
    }

    private void initScrollView() {
        if( isInited() ) {
            scrollView.removeAllViews();
            LinearLayout scrollViewParent = new LinearLayout( getContext() );
            scrollViewParent.setOrientation( LinearLayout.VERTICAL );

            int spaceHeight = cellHeight * spaceCellCount;
            scrollViewParent.addView( getSpace( spaceHeight ) );
            for( int i = 0; i < items.size(); ++i )
                scrollViewParent.addView( getTextView( i ) );
            if( itemsToShow % 2 == 0 )
                spaceHeight -= cellHeight;
            scrollViewParent.addView( getSpace( spaceHeight ) );

            scrollView.addView( scrollViewParent );
            scrollViewParent.getViewTreeObserver().addOnPreDrawListener( new ViewTreeObserver.OnPreDrawListener() {
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

    private View getSpace( int height ) {
        Space space = new Space( getContext() );
        space.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT ) );
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)space.getLayoutParams();
        p.height = height;
        space.setLayoutParams( p );
        return space;
    }

    private boolean isInited() {
        return isOnSizeChangedFinished && isListInited;
    }

    @NonNull
    private TextView getTextView( int i ) {
        TextView textView = new TextView( getContext() );
        switch( listItemType ) {
            case INT:
                textView.setText( "" + getIntItems().get( i ) );
                break;
            case STRING:
                textView.setText( getStringItems().get( i ) );
                break;
        }
        textView.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT ) );
        textView.setTextSize( TypedValue.COMPLEX_UNIT_SP, textSize );
        textView.setTextColor( textColor );
        textView.setGravity( CENTER );
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)textView.getLayoutParams();
        p.height = cellHeight;
        textView.setLayoutParams( p );
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
        l.onValueChange( getValue( newIndex ) );
    }

    private int getValue( int index ) {
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

        void onValueChange( int newValue ); // if we use the Int implementation, send the Value itself, otherwise send the index of the selected String
    }

}
