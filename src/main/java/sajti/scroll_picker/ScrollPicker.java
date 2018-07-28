package sajti.scroll_picker;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.support.v4.widget.NestedScrollView;
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
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static android.view.Gravity.CENTER;

/**
 * Made by github.com/tomeeeS
 * <p>
 * Implementation notes:
 * Do Not try to refactor the funcionality of making the first and last items selectable by refactoring out the empty items into a view with
 * margin on the first/last couple of items! The margins will not be modifiable correctly during scrolling and thus messing up the selected item because some
 * items will have the bigger margin of the first/last items due to the listView's reusing of item view layouts.
 */
public class ScrollPicker extends LinearLayout {

    public static final int LAYOUT = R.layout.scroll_picker;

    public static final int SHOWN_ITEM_COUNT_DEFAULT = 3;
    public static final boolean IS_SET_NEXT_OR_PREVIOUS_ITEM_ENABLED = true;
    public static final int SCROLL_STOP_CHECK_INTERVAL_MS = 20;
    public static final int POSITIVE_SCROLL_CORRECTION = 1;
    public static final int TEXT_SIZE_DEFAULT = 18;
    public static final int SELECTOR_HEIGHT_CORRECTION = 1;
    public static final int SCROLL_BY_DURATION_DEFAULT = 120;
    public static final int SELECTOR_COLOR_DEFAULT = Color.parseColor( "#116b2b66" );
    public static final int TEXT_COLOR_DEFAULT = Color.BLACK;
    private final float TOUCH_SLOP = ViewConfiguration.get( getContext() ).getScaledTouchSlop();

    protected ArrayList items;
    private Rect selectPreviousItemRect;
    private Rect selectNextItemRect;
    private ListItemType listItemType;
    private NestedScrollView scrollView;
    private Context context;
    private int itemsToShow = SHOWN_ITEM_COUNT_DEFAULT;
    private int spaceCellCount; // how many cells equate to the height of the space before (and after) the text views
    private int cellHeight;
    private List< OnValueChangeListener > onValueChangeListeners = new LinkedList<>();
    private Paint selectorPaint;
    private Rect selectorRect;
    private float mStartY;
    private boolean isExternalValueChange = true;
    private boolean isOnSizeChangedFinished = false;
    private boolean isListInited = false;
    private Integer selectedIndex = 0;
    private Runnable scrollerTask;
    private int lastScrollY;
    private AtomicInteger scrollYTo = new AtomicInteger();
    private float textSize;
    private int textColor;

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

    // external setValue, no need to trigger value changed callback
    public void setValue( Integer value ) {
        if( !value.equals( selectedIndex ) ) {
            isExternalValueChange = true;
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

    public Integer getValue() {
        return selectedIndex;
    }

    public void setSelectorColor( int selectorColor ) {
        selectorPaint.setColor( selectorColor );
    }

    public void setTextSize( float textSize ) {
        this.textSize = textSize;
        initScrollView();
    }

    public void setList( ListItemType listItemType, ArrayList items ) {
        isListInited = true;
        this.listItemType = listItemType;
        this.items = new ArrayList( items );
        initScrollView();
    }

    public void addOnValueChangedListener( OnValueChangeListener onValueChangeListener ) {
        onValueChangeListeners.add( onValueChangeListener );
    }

    public void setShownItemCount( int itemsToShow ) {
        this.itemsToShow = itemsToShow;
        spaceCellCount = itemsToShow / 2;
        initSelectorAndCellHeight();
        initScrollView();
    }

    public void setTextColor( int textColor ) {
        this.textColor = textColor;
        initScrollView();
    }

    public void removeOnValueChangedListener( OnValueChangeListener onValueChangeListener ) {
        onValueChangeListeners.remove( onValueChangeListener );
    }
    // corrections are necessary at the end of scrolling to set ourself to a valid position

    @Override
    public boolean dispatchTouchEvent( MotionEvent event ) {
        switch( event.getAction() ) {
            case MotionEvent.ACTION_DOWN: // this isn't for going down ;)
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

    // go 1 down or up on touching above or below the selection area
    @Override
    public boolean onTouchEvent( MotionEvent event ) {
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

    private void restartScrollStopCheck() {
        postDelayed( scrollerTask, SCROLL_STOP_CHECK_INTERVAL_MS );
    }

    private void initValues( AttributeSet attrs ) {
        TypedArray attributesArray = context.obtainStyledAttributes( attrs, R.styleable.ScrollPicker );
        setSelectorColor( attributesArray.getColor( R.styleable.ScrollPicker_selectorColor, SELECTOR_COLOR_DEFAULT ) );
        selectorPaint.setStyle( Paint.Style.FILL );
        itemsToShow = attributesArray.getInt( R.styleable.ScrollPicker_itemsToShow, SHOWN_ITEM_COUNT_DEFAULT );
        textSize = attributesArray.getFloat( R.styleable.ScrollPicker_textSize, TEXT_SIZE_DEFAULT );
        textColor = attributesArray.getInt( R.styleable.ScrollPicker_textColor, TEXT_COLOR_DEFAULT );
        attributesArray.recycle();
    }

    private void scrollYTo( int scrollYTo ) {
        ObjectAnimator scrollYAnimator = ObjectAnimator.ofInt( scrollView, "scrollY", scrollYTo ).
                setDuration( SCROLL_BY_DURATION_DEFAULT );
        scrollYAnimator.setInterpolator( new LinearInterpolator() );
        scrollYAnimator.start();
    }

    private void init() {
        LayoutInflater inflater = (LayoutInflater)context.getSystemService( Context.LAYOUT_INFLATER_SERVICE );
        inflater.inflate( LAYOUT, this, true );
        selectorPaint = new Paint();

        scrollerTask = () -> {
            int newPosition = scrollView.getScrollY();
            if( lastScrollY == newPosition ) { // has stopped
                scrollYTo.set( lastScrollY );
                selectNearestItemOnScrollStop();
            } else {
                lastScrollY = scrollView.getScrollY();
                restartScrollStopCheck();
            }
        };
        scrollView = findViewById( R.id.scrollView );
    }

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
        int visibleHeightOfItem = ( rect.height() > cellHeight ) ? rect.height() % cellHeight : rect.height();
        int scrollYby;
        if( Math.abs( visibleHeightOfItem ) <= cellHeight / 2 ) { // % cellHeight: the space view's height is multiple of cellHeight
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
                    getHeight() - cellHeight * spaceCellCount,
                    getWidth(),
                    getHeight() );
            post( new Runnable() {
                @Override
                public void run() {
                    initScrollView();
                }
            } );
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
                public boolean onPreDraw() {
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
        Space space = new Space( getContext() ); // TODO
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
        l.onValueChange( listItemType == ListItemType.STRING ?
                newIndex :
                getIntItems().get( newIndex ) );
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
