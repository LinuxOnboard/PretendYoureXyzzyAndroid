package com.gianlu.pretendyourexyzzy.Cards;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.support.annotation.AttrRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gianlu.commonutils.SuperTextView;
import com.gianlu.pretendyourexyzzy.NetIO.Models.BaseCard;
import com.gianlu.pretendyourexyzzy.NetIO.Models.Card;
import com.gianlu.pretendyourexyzzy.R;

public class PyxCard extends CardView {
    private final ICard listener;
    private final PyxCardsGroupView.Action mainAction;
    private BaseCard card;
    private int width;

    public PyxCard(@NonNull Context context) {
        this(context, null, 0);
    }

    public PyxCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PyxCard(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.listener = null;
        this.mainAction = null;
    }

    public PyxCard(Context context, BaseCard card, @Nullable PyxCardsGroupView.Action mainAction, @Nullable ICard listener) {
        super(context);
        this.card = card;
        this.mainAction = mainAction;
        this.listener = listener;
        init();
    }

    private void init() {
        removeAllViews();
        if (card == null) {
            setVisibility(GONE);
            return;
        }

        LayoutInflater.from(getContext()).inflate(R.layout.pyx_card, this, true);

        width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 156, getResources().getDisplayMetrics());

        setCardElevation((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));

        TypedArray a = getContext().getTheme().obtainStyledAttributes(R.style.AppTheme, new int[]{android.R.attr.selectableItemBackground});
        int attributeResourceId = a.getResourceId(0, 0);
        a.recycle();
        setForeground(ContextCompat.getDrawable(getContext(), attributeResourceId));

        Typeface roboto = Typeface.createFromAsset(getContext().getAssets(), "fonts/Roboto-Medium.ttf");
        int colorAccent = ContextCompat.getColor(getContext(), R.color.colorAccent);

        LinearLayout content = findViewById(R.id.pyxCard_content);
        LinearLayout unknown = findViewById(R.id.pyxCard_unknown);

        if (card.isUnknown()) {
            unknown.setVisibility(VISIBLE);
            content.setVisibility(GONE);
        } else {
            unknown.setVisibility(GONE);
            content.setVisibility(VISIBLE);

            if (card instanceof Card) {
                if (((Card) card).isWinner()) setCardBackgroundColor(colorAccent);
                else setCardBackgroundColor(card.getNumPick() != -1 ? Color.BLACK : Color.WHITE);
            }

            SuperTextView text = content.findViewById(R.id.pyxCard_text);
            text.setTextColor(card.getNumPick() != -1 ? Color.WHITE : Color.BLACK);
            text.setTypeface(roboto);
            TextView watermark = content.findViewById(R.id.pyxCard_watermark);
            watermark.setTextColor(card.getNumPick() != -1 ? Color.WHITE : Color.BLACK);
            SuperTextView numPick = content.findViewById(R.id.pyxCard_numPick);
            numPick.setTextColor(card.getNumPick() != -1 ? Color.WHITE : Color.BLACK);
            SuperTextView numDraw = content.findViewById(R.id.pyxCard_numDraw);
            numDraw.setTextColor(card.getNumPick() != -1 ? Color.WHITE : Color.BLACK);

            text.setHtml(card.getText());
            watermark.setText(card.getWatermark());
            if (card.getNumPick() != -1) {
                numPick.setHtml(R.string.numPick, card.getNumPick());
                if (card.getNumDraw() > 0) numDraw.setHtml(R.string.numDraw, card.getNumDraw());
                else numDraw.setVisibility(GONE);
            } else {
                numDraw.setVisibility(GONE);
                numPick.setVisibility(GONE);
            }

            final ImageButton action = content.findViewById(R.id.pyxCard_action);
            if (mainAction == null) {
                action.setVisibility(GONE);
            } else {
                switch (mainAction) {
                    case SELECT:
                        action.setVisibility(GONE);
                        break;
                    case DELETE:
                        action.setVisibility(VISIBLE);
                        action.setImageResource(R.drawable.ic_delete_black_48dp);
                        action.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                if (listener != null) listener.onDelete();
                            }
                        });
                        break;
                    case TOGGLE_STAR:
                        action.setVisibility(VISIBLE);
                        action.setImageResource(R.drawable.ic_star_black_48dp);
                        action.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                if (listener != null) listener.onToggleStar();
                            }
                        });
                        break;
                }
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    public BaseCard getCard() {
        return card;
    }

    public void setCard(@Nullable BaseCard card) {
        this.card = card;
        init();
    }

    interface ICard {
        void onDelete();

        void onToggleStar();
    }
}
