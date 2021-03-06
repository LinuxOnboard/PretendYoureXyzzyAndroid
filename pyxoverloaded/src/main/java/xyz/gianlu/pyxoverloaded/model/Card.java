package xyz.gianlu.pyxoverloaded.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gianlu.commonutils.CommonUtils;

import org.jetbrains.annotations.Contract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Card {
    private static final int CARD_TYPE_BLACK = 0;
    private static final int CARD_TYPE_WHITE = 1;
    public final String text;
    public final String watermark;
    public final int type;
    public final String creator;
    public final Long remoteId;

    public Card(@NonNull JSONObject obj, @Nullable String parentCreator) throws JSONException {
        text = obj.getString("text");
        watermark = obj.getString("watermark");
        type = obj.getInt("type");
        remoteId = CommonUtils.optLong(obj, "id");

        String creatorTmp = CommonUtils.optString(obj, "creator");
        if (creatorTmp == null) creator = parentCreator;
        else creator = creatorTmp;
    }

    private Card(String text, String watermark, int type, String creator, Long remoteId) {
        this.text = text;
        this.watermark = watermark;
        this.type = type;
        this.creator = creator;
        this.remoteId = remoteId;
    }

    @NonNull
    public static JSONObject toSyncJson(boolean black, @NonNull String[] text) throws JSONException {
        JSONObject card = new JSONObject();
        card.put("type", black ? Card.CARD_TYPE_BLACK : Card.CARD_TYPE_WHITE);
        card.put("text", CommonUtils.join(text, "____"));
        return card;
    }

    @NonNull
    public static List<Card> parse(UserProfile.CustomDeckWithCards deck, @NonNull JSONArray array) throws JSONException {
        List<Card> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++)
            list.add(new Card(array.getJSONObject(i), deck.owner));
        return list;
    }

    @NonNull
    @Contract(value = "_, _, _, _ ,_-> new", pure = true)
    public static Card from(@NonNull String text, @NonNull String watermark, boolean black, @Nullable String creator, long remoteId) {
        return new Card(text, watermark, black ? CARD_TYPE_BLACK : CARD_TYPE_WHITE, creator, remoteId);
    }

    public boolean black() {
        return type == CARD_TYPE_BLACK;
    }

    @NonNull
    public JSONObject toSyncJson() throws JSONException {
        if (remoteId == null) throw new IllegalStateException();

        return new JSONObject()
                .put("id", remoteId)
                .put("type", type)
                .put("text", text);
    }

    @NonNull
    @Contract("_ -> new")
    public Card update(@NonNull String text) {
        return new Card(text, watermark, type, creator, remoteId);
    }
}
