package com.github.tomeees.scrollpicker;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;
import androidx.databinding.BindingAdapter;
import androidx.databinding.Observable;
import androidx.databinding.ObservableField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static android.view.Gravity.CENTER;

/*
 * Made by Tamás Sajti  tamas.sajti.dev@gmail.com
 * To have a customizable and data-bindable NumberPicker.
 *
 * project home:                          https://github.com/tomeeeS/ScrollPickerDemo
 *
 * Glossary:
 * value    - In case of Ints the value you'll get back will be the selected int item itself, in the other cases it will be the item's index.
 * selector - The visual indication about the currently selected item at the middle of the view
 *
 * Notes:
 * - You can't have the items displayed in a loop (like as with wrapSelectorWheel in NumberPicker).
 * - The user can't edit the items from the UI.
 *
 *
 * Licence: Apache-2.0 (do with it whatever you please)
 *
 *
 * Implementation advices: (for those who might want to make changes)
 * - Do Not try to use setOnScrollChangeListener for listening for scroll stop. Unfortunately it isn't possible, android doesn't give any callbacks for that
 *      and we can't determine it from setOnScrollChange because there is no sensible threshold for scroll value change (y - oldY) that would be low enough to
 *      detect this event. There is no call to happen when oldY equals y - that would tell us clearly that the scrolling has stopped -
 *      and sometimes the last change is as much as 6 pixels so there is also no sensible threshold value.
 *      This scrollerTask solution is the best I could come up with. (check for slowing scroll from time to time and when it's in a threshold value we stop the
 *      current scrolling and start the correction scroll).
 *
 * - I tried to add a data-bindable selected item index attribute to be able to set the selected item with its index in the case of integers too, but
 *      it "clashed" with the value too much, for example when you set the value, in the code you also have to set the selected index and vice versa, and
 *      the implementation of setting the selected index was the easiest by setting the value, so a loop was created. I felt introducing more flags that
 *      correspond to what is getting set where and what not to update when was really doing more harm by making the code less clean and readable.
 *      And this is a marginal use case anyway, the value is good enough for synchronizing with the view model.
 */

/**
 * Customizable and data-bindable NumberPicker-like UI element.
 */
public class ScrollPicker extends LinearLayout {

    protected static final int LAYOUT = R.layout.scroll_picker;

    protected static final int SHOWN_ITEM_COUNT_DEFAULT = 3;
    protected static final boolean IS_SET_NEXT_OR_PREVIOUS_ITEM_ENABLED = true;
    protected static final int SCROLL_STOP_CHECK_INTERVAL_MS = 20;
    protected static final int TEXT_SIZE_DEFAULT = 16;
    protected static final float SELECTED_TEXT_SIZE_DEFAULT = TEXT_SIZE_DEFAULT;
    protected static final int SCROLL_INTO_PLACE_DURATION_MS_DEFAULT = 120;
    protected static final int SELECTOR_STYLE_DEFAULT_INDEX = 2; // corresponds to the classic style
    protected static final int SELECTOR_STROKE_WIDTH = 4;
    protected static final int SELECTED_INDEX_DEFAULT = 0;
    protected static final int AUTO_SIZE_MIN_TEXT_SIZE = 2;
    protected static final int AUTO_SIZE_STEP_GRANULARITY = 1;
    protected static int SELECTOR_COLOR_DEFAULT;
    protected static int TEXT_COLOR_DISABLED;
    protected static int TEXT_COLOR_DEFAULT;
    protected int SELECTED_TEXT_COLOR_DEFAULT;
    protected final float TOUCH_SLOP = ViewConfiguration.get( getContext() ).getScaledTouchSlop();

    protected ArrayList items; // the items that we display
    protected Rect selectPreviousItemRect; // the touch area rectangle for the select previous item functionality
    protected Rect selectNextItemRect; // the touch area rectangle for the select next item functionality
    protected ListItemType listItemType;
    protected Context context;
    int shownItemCount = SHOWN_ITEM_COUNT_DEFAULT; // how many items can be shown at a time
    protected int spaceCellCount; // how many cells equate to the height of the space before (and after) the text views
    int cellHeight; // (approximate) height of one item
    protected List< OnValueChangeListener > onValueChangeListeners = new LinkedList<>();
    protected Paint selectorPaint;
    protected Rect selectorRect;
    protected float mStartY;
    protected boolean isExternalValueChange = false;
    protected boolean isOnSizeChangedFinished = false;
    protected boolean isListInited = false;
    protected int selectedItemIndex = SELECTED_INDEX_DEFAULT;
    protected Runnable scrollerTask;
    protected int lastScrollY;
    protected AtomicInteger scrollYTo = new AtomicInteger();
    protected float textSize;
    protected int enabledTextColor, selectedTextColor;
    protected boolean isEnabled;
    protected Integer storedValue; // was there a value set yet and what was it
    protected SelectorStyle selectorStyle;
    protected int selectorRectHorizontalInset; // we always draw a rectangle for the selector, we just set the left and right coordinates for it according to style
    protected float selectedTextSize;
    protected boolean hasSelectedTextColorBeenSetByUser = false;
    protected boolean hasSelectedTextSizeBeenSetByUser = false;
    private boolean isTextBold;
    private float selectorLineWidth;

    protected NestedScrollView scrollView; // the parent view in which we have the elements in a vertical LinearLayout. Good for scrolling.
    protected LinearLayout itemsLayout;
    protected View correctionViewTop; // these are to take up the space which is left when total view height is not divisible by shownItemCount
    protected View correctionViewBottom;
    protected List<AppCompatTextView> textViews;
    protected AppCompatTextView previouslySelectedTextView;

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
     * @return If the list we set was such that its items are Integers, then the returned value corresponds to the item's int value,
     *         otherwise it is the index of the selected item in the list.
     */
    public int getValue() {
        return getValueForIndex(selectedItemIndex);
    }

    /**
     * Sets the selected item. Can be data-bound (2-way).
     * Throws {@link WrongValueException} if an invalid value is being set.
     *
     * @param value If the list we set was such that its items are Integers, then the value corresponds to the item's int value,
     *              otherwise it is the index of the selected item in the list.
     */
    public void setValue( int value ) {
        if( isListInited ) {
            if( value != getValueForIndex(selectedItemIndex) ) {
                isExternalValueChange = true; // external setValue, no need to trigger value changed callback
                selectItemFromValue( value );
                if( isInitReady() ) {
                    scrollYTo( selectedItemIndex * cellHeight );
                    invalidate();
                }
                isExternalValueChange = false;
            }
        } else
            storedValue = value;
    }

    /**
     * Gets the selected item's displayed String 'value'.
     */
    public String getSelectedItemText() {
        return getContentDescription().toString();
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
            updateTextViewsStyle();
        }
    }

    /**
     * Sets if all the item texts should be bold or not.
     *
     * @param isTextBold Will be bold if true, not if false.
     */
    public void setTextBold( boolean isTextBold ) {
        this.isTextBold = isTextBold;
    }

    /**
     * Sets the selector's color. The selector is the visual indication about the currently selected item at the middle of the view.
     *
     * @param selectorColor Resolved color.
     */
    public void setSelectorColor( int selectorColor ) {
        selectorPaint.setColor( selectorColor );
        invalidate();
    }

    /**
     * Returns the text size of the list items displayed.
     */
    public float getTextSize() {
        return textSize;
    }

    /**
     * Sets the text size of the list items displayed, sets it for the selected one also if that hasn't been explicitly set.
     */
    public void setTextSize( float textSize ) {
        this.textSize = textSize;
        updateTextViewsStyle();
    }

    /**
     * Sets the text size of the list items displayed.
     */
    public void setSelectedTextSize( float selectedTextSize ) {
        hasSelectedTextSizeBeenSetByUser = true;
        this.selectedTextSize = selectedTextSize;
        updateTextViewsStyle();
    }

    /**
     * Data binding helper method for {@link #setItems(Collection)}.
     */
    public void setItems( final ObservableField< ? extends Collection > items ) {
        setItems( items.get() );
        items.addOnPropertyChangedCallback( new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged( Observable sender, int propertyId ) {
                setItems( items.get() );
            }
        } );
    }

    /**
     * Sets the list whose items this view displays. Can be data-bound.
     *
     * @param items Must be non-empty.
     *              If the collection is such that its items are Integers, then the value that you can set to this view with {@link #setValue(int)}
     *              corresponds to the item's int value, otherwise it is the index of the selected item in the list.
     */
    public void setItems( Collection items ) {
        ArrayList arrayList = new ArrayList( items );
        setItemsItemType( arrayList );
        this.items = arrayList;
        isListInited = true;
        initScrollView();
        isExternalValueChange = true;
        if( storedValue != null ) {  // if we had a value set before, we can set it now that the list was inited
            setValue( storedValue );
            storedValue = null;
        } else
            selectNewItem( SELECTED_INDEX_DEFAULT );
        isExternalValueChange = false;
    }

    /**
     * Sets a range of integers as the list whose items this view displays.
     *
     * @param fromInclusive  The start point of the range.
     * @param toInclusive    The end point of the range. Must be greater than fromInclusive.
     */
    public void setItemsIntRange( int fromInclusive, int toInclusive ) {
        ArrayList< Integer > list = new ArrayList<>( toInclusive - fromInclusive + 1 );
        for( int i = fromInclusive; i < toInclusive + 1; i++ )
            list.add( i );
        setItems( list );
    }

    /**
     * Sets how many items can be shown at a time
     */
    public void setShownItemCount( int shownItemCount ) {
        this.shownItemCount = shownItemCount;
        spaceCellCount = shownItemCount / 2;
        initSelectorAndCellHeight();
    }

    /**
     * Sets the text color of the not selected items in the list, sets it for the selected one also if that hasn't been explicitly set.
     *
     * @param textColor Resolved color.
     */
    public void setTextColor( int textColor ) {
        enabledTextColor = textColor;
        updateTextViewsStyle();
    }

    /**
     * Sets the text color of the selected item.
     *
     * @param selectedTextColor Resolved color.
     */
    public void setSelectedTextColor( int selectedTextColor ) {
        hasSelectedTextColorBeenSetByUser = true;
        this.selectedTextColor = selectedTextColor;
        updateTextViewsStyle();
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
                    selectorRectHorizontalInset = (int)selectorLineWidth / 2;
                    selectorPaint.setStyle( Paint.Style.FILL_AND_STROKE );
                    break;
                case RECTANGLE:
                    selectorRectHorizontalInset = (int)selectorLineWidth * 2; // we pull the left and right sides in a little bit
                    selectorPaint.setStyle( Paint.Style.STROKE );
                    break;
                case CLASSIC:
                default:
                    selectorRectHorizontalInset = 0;
                    selectorPaint.setStyle( Paint.Style.STROKE );
                    break;
//                case CLASSIC_SHORT: // same as classic but with only the middle third of width?
//                    break;
            }
            setSelectorRect();
            invalidate();
        }
    }

    /**
     * Sets the selector lines' width.
     * @param selectorLineWidth The width. Default is 4. If 0, no selector will be drawn.
     */
    public void setSelectorLineWidth( float selectorLineWidth ) {
        this.selectorLineWidth = selectorLineWidth;
        selectorPaint.setStrokeWidth( selectorLineWidth );
        invalidate();
    }

    /**
     * Selects the next item if the currently selected isn't the last one.
     */
    public void selectNextItem() {
        if( selectedItemIndex < items.size() - 1 ) {
            scrollYBy( cellHeight );
        }
    }

    /**
     * Selects the previous item if the currently selected isn't the first one.
     */
    public void selectPreviousItem() {
        if( selectedItemIndex > 0 ) {
            scrollYBy( -cellHeight );
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
        drawSelector( canvas );
        super.dispatchDraw( canvas );
    }

    private void updateTextViewsStyle() {
        if( isInitReady() )
            for( int i = 0; i < items.size(); ++i ) {
                AppCompatTextView textView = textViews.get( i );
                setTextViewStyle( i, textView );
                textView.invalidate();
            }
    }

    private void drawSelector( Canvas canvas ) {
        if( selectorLineWidth > 0 )
            if( selectorStyle == SelectorStyle.CLASSIC ) {
                canvas.drawLine( selectorRect.left, selectorRect.top, selectorRect.right, selectorRect.top, selectorPaint );
                canvas.drawLine( selectorRect.left, selectorRect.bottom, selectorRect.right, selectorRect.bottom, selectorPaint );
            }
            else
                canvas.drawRect( selectorRect, selectorPaint );
    }

    protected void selectItemFromValue( int index ) {
        switch( listItemType ) {
            case INT:
                selectItem( getIndexOfValue( index ) );
                break;
            case OTHER:
                selectItem( index );
                break;
        }
    }

    protected void setItemsItemType( ArrayList items ) {
        if( items.get( 0 ) instanceof Integer )
            this.listItemType = ListItemType.INT;
        else
            this.listItemType = ListItemType.OTHER;
    }

    // for testing
    int getListScrollY() {
        return scrollView.getScrollY();
    }

    protected int getIndexOfValue( int value ) {
        ArrayList< Integer > intItems = getIntItems();
        if( intItems.contains( value ) )
            return intItems.indexOf( value );
        throw new WrongValueException( String.format( "Tried to set value %s which wasn't in the items.", value ) );
    }

    protected void restartScrollStopCheck() {
        postDelayed( scrollerTask, SCROLL_STOP_CHECK_INTERVAL_MS );
    }

    protected void initValues( AttributeSet attrs ) {
        TEXT_COLOR_DISABLED = ContextCompat.getColor( context, R.color.textColorDisabled );
        TEXT_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.textColorDefault );
        SELECTED_TEXT_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.textColorDefault );
        SELECTOR_COLOR_DEFAULT = ContextCompat.getColor( context, R.color.selectorColorDefault );

        isEnabled = true;
        selectorPaint = new Paint();

        TypedArray attributesArray = context.obtainStyledAttributes( attrs, R.styleable.ScrollPicker );

        setSelectorLineWidth( attributesArray.getFloat( R.styleable.ScrollPicker_selectorLineWidth, SELECTOR_STROKE_WIDTH ) );
        setSelectorColor( attributesArray.getColor( R.styleable.ScrollPicker_selectorColor, SELECTOR_COLOR_DEFAULT ) );
        setSelectorStyle( SelectorStyle.values()[ attributesArray.getInt( R.styleable.ScrollPicker_selectorStyle, SELECTOR_STYLE_DEFAULT_INDEX ) ] );
        setShownItemCount( attributesArray.getInt( R.styleable.ScrollPicker_shownItemCount, SHOWN_ITEM_COUNT_DEFAULT ) );

        setTextSize( attributesArray.getFloat( R.styleable.ScrollPicker_textSize, TEXT_SIZE_DEFAULT ) );
        if( attributesArray.hasValue( R.styleable.ScrollPicker_selectedTextSize ) ) {
            setSelectedTextSize( attributesArray.getFloat( R.styleable.ScrollPicker_selectedTextSize, SELECTED_TEXT_SIZE_DEFAULT ) );
        }
        if( attributesArray.hasValue( R.styleable.ScrollPicker_selectedTextColor ) ) {
            setSelectedTextColor( attributesArray.getInt( R.styleable.ScrollPicker_selectedTextColor, SELECTED_TEXT_COLOR_DEFAULT ) );
        }
        setTextColor( attributesArray.getInt( R.styleable.ScrollPicker_textColor, TEXT_COLOR_DEFAULT ) );
        setEnabled( attributesArray.getBoolean( R.styleable.ScrollPicker_isEnabled, true ) );
        setTextBold( attributesArray.getBoolean( R.styleable.ScrollPicker_isTextBold, false ) );

        attributesArray.recycle();
    }

    protected void scrollYTo( int scrollYTo ) {
        ObjectAnimator scrollYAnimator = ObjectAnimator.ofInt( scrollView, "scrollY", scrollYTo ).
                setDuration( SCROLL_INTO_PLACE_DURATION_MS_DEFAULT );
        scrollYAnimator.setInterpolator( new LinearInterpolator() );
        scrollYAnimator.start();
    }

    protected void init() {
        LayoutInflater inflater = (LayoutInflater)context.getSystemService( Context.LAYOUT_INFLATER_SERVICE );
        inflater.inflate( LAYOUT, this, true );

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
    protected void selectNearestItemOnScrollStop() {
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

    protected int getScrollYby( int visibleHeightOfItem ) {
        int scrollYby;
        if( Math.abs( visibleHeightOfItem ) <= cellHeight / 2 ) {
            scrollYby = visibleHeightOfItem;
        } else {
            scrollYby = visibleHeightOfItem - cellHeight;
        }
        return scrollYby;
    }

    @NonNull
    protected Rect getFirstVisibleRect( int firstVisibleItemIndex ) {
        View child = itemsLayout.getChildAt( firstVisibleItemIndex );
        Rect firstVisibleRect = new Rect( 0, 0, child.getWidth(), child.getHeight() );
        itemsLayout.getChildVisibleRect( child, firstVisibleRect, null );
        return firstVisibleRect;
    }

    protected int getFirstVisibleItemIndex() {
        int cellCount = scrollView.getScrollY() / cellHeight;
        int spaceHeight = spaceCellCount * cellHeight;
        int firstVisibleItemIndex;
        if( scrollView.getScrollY() > spaceHeight )
            firstVisibleItemIndex = cellCount - ( spaceCellCount - 1 );
        else
            firstVisibleItemIndex = 0;
        return firstVisibleItemIndex;
    }

    protected void initSelectorAndCellHeight() {
        cellHeight = getHeight() / shownItemCount;
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

    protected void setSelectorRect() {
        int cellHeightCeiling = (int)Math.ceil( (double)getHeight() / (double)shownItemCount );
        selectorRect = new Rect( selectorRectHorizontalInset,
                cellHeightCeiling * spaceCellCount,
                getWidth() - selectorRectHorizontalInset,
                cellHeightCeiling * ( spaceCellCount + 1 ) );
    }

    protected void initScrollView() {
        if( isInitReady()) {
            scrollView.removeAllViews();
            int scrollViewHeight = cellHeight * shownItemCount;
            setViewHeight( scrollView, scrollViewHeight );
            setCorrectionViewsHeights( scrollViewHeight );

            initItemsLayout();
            fillItemsLayout();
            addInitialValueScroll();

            scrollView.invalidate();
            scrollView.requestLayout();
        }
    }

    private void addInitialValueScroll() {
        itemsLayout.getViewTreeObserver().addOnPreDrawListener( new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() { // sets the position to the selected item without animation
                scrollView.getViewTreeObserver().removeOnPreDrawListener( this );
                int scrollYTo = selectedItemIndex * cellHeight;
                scrollView.scrollTo( 0, scrollYTo );
                ScrollPicker.this.scrollYTo.set( scrollYTo );
                return false;
            }
        } );
    }

    private void fillItemsLayout() {
        textViews = new ArrayList<>(items.size());
        int spaceHeight = cellHeight * spaceCellCount;
        itemsLayout.addView( getSpace( spaceHeight ) );
        for( int i = 0; i < items.size(); ++i ) {
            AppCompatTextView textView = getTextView(i);
            itemsLayout.addView(textView);
            textViews.add(textView);
        }
        if( shownItemCount % 2 == 0 )
            spaceHeight -= cellHeight;
        itemsLayout.addView( getSpace( spaceHeight ) );

        scrollView.addView( itemsLayout );
    }

    private void initItemsLayout() {
        itemsLayout = new LinearLayout( scrollView.getContext() );
        itemsLayout.setOrientation( LinearLayout.VERTICAL );
    }

    protected void setCorrectionViewsHeights( int scrollViewHeight ) {
        setViewHeight( correctionViewTop, calculateViewHeight( false, scrollViewHeight ) );
        setViewHeight( correctionViewBottom, calculateViewHeight( true, scrollViewHeight ) );
    }

    protected int calculateViewHeight( boolean isBottom, int scrollViewHeight ) {
        int heightHalf = ( getHeight() - scrollViewHeight ) / 2;
        int heightMod = ( getHeight() - scrollViewHeight ) % 2;
        return heightHalf + (isBottom ? heightMod : 0);
    }

    protected View getSpace( int height ) {
        View space = new View( getContext() );
        space.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT ) );
        setViewHeight( space, height );
        return space;
    }

    protected void setViewHeight( View space, int height ) {
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)space.getLayoutParams();
        p.height = height;
        space.setLayoutParams( p );
    }

    protected boolean isInitReady() {
        return isOnSizeChangedFinished && isListInited;
    }

    @NonNull
    protected AppCompatTextView getTextView( int itemIndex ) {
        AppCompatTextView textView = new AppCompatTextView( getContext() );
        setTextViewLayoutParams( textView );
        setTextViewStyle( itemIndex, textView );
        setText( itemIndex, textView );
        textView.invalidate();
        return textView;
    }

    protected void setTextViewStyle( int itemIndex, AppCompatTextView textView ) {
        if( itemIndex == selectedItemIndex) {
            float maxTextSize = hasSelectedTextSizeBeenSetByUser ? selectedTextSize : textSize;
            setAutosizeTextSize( textView, (int)maxTextSize );
            int textColorForSelectedItem;
            if( isEnabled )
                textColorForSelectedItem = hasSelectedTextColorBeenSetByUser ? selectedTextColor : enabledTextColor;
            else
                textColorForSelectedItem = TEXT_COLOR_DISABLED;
            textView.setTextColor( textColorForSelectedItem );
        } else {
            setAutosizeTextSize( textView, (int)textSize );
            textView.setTextColor( isEnabled ? enabledTextColor : TEXT_COLOR_DISABLED );
        }
        if( isTextBold )
            textView.setTypeface( textView.getTypeface(), Typeface.BOLD );
    }

    private void setAutosizeTextSize( AppCompatTextView textView, int maxTextSize ) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            textView, AUTO_SIZE_MIN_TEXT_SIZE, maxTextSize, AUTO_SIZE_STEP_GRANULARITY, TypedValue.COMPLEX_UNIT_SP );
    }

    protected void setTextViewLayoutParams( AppCompatTextView textView ) {
        textView.setLayoutParams( new LayoutParams( LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT ) );
        int verticalAlignmentCorrection = (int) -( textView.getTextSize() / 8 );
        // verticalAlignmentCorrection: text is not centered for some reason and it needs correction
        int horizontalPadding = selectorRectHorizontalInset + (int)selectorLineWidth;
        textView.setPadding( horizontalPadding, verticalAlignmentCorrection, horizontalPadding, 0 );
        MarginLayoutParams p = (MarginLayoutParams)textView.getLayoutParams();
        p.height = cellHeight;
        textView.setLayoutParams( p );
        textView.setGravity( CENTER );
    }

    protected void setText( int itemIndex, AppCompatTextView textView ) {
        switch( listItemType ) {
            case INT:
                textView.setText( "" + getIntItems().get( itemIndex ) );
                break;
            case OTHER:
                textView.setText( items.get( itemIndex ).toString() );
                break;
        }
    }

    protected void scrollYBy( int scrollYby ) {
        scrollYTo.set( scrollYTo.get() + scrollYby );
        scrollYTo( scrollYTo.get() );
        selectItem( scrollYTo.get() / cellHeight );
    }

    protected void selectItem( int newIndex ) {
        if( selectedItemIndex != newIndex )
            selectNewItem( newIndex );
    }

    private void selectNewItem( int newIndex ) {
        validateIndex( newIndex );
        selectedItemIndex = newIndex;
        setContentDescription( items.get(selectedItemIndex).toString() );
        if( !isExternalValueChange ) {
            for( OnValueChangeListener l : onValueChangeListeners )
                sendOnValueChanged( newIndex, l );
        }
        scrollYTo.set( newIndex * cellHeight );
        updateTextViewsStyle();
    }

    // if we use the Int implementation, send the Value itself, otherwise send the index of the selected value
    protected void sendOnValueChanged( int newIndex, OnValueChangeListener l ) {
        l.onValueChange( getValueForIndex( newIndex ) );
    }

    protected int getValueForIndex( int index ) {
        validateIndex( index );
        return listItemType == ListItemType.OTHER ?
            index :
            getIntItems().get( index );
    }

    private void validateIndex( int index ) {
        if( index < 0 || index >= items.size() )
            throw new WrongValueException( String.format( "Tried to set invalid index %s.", index ) );
    }

    protected ArrayList< Integer > getIntItems() {
        return (ArrayList< Integer >)items;
    }

    enum ListItemType {
        INT, OTHER
    }

    public interface OnValueChangeListener {
        void onValueChange( int newValue ); // if we use the Int implementation, send the value itself, otherwise send the index of the selected value
    }

}
