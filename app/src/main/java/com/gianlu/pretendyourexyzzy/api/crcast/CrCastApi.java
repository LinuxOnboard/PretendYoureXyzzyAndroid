package com.gianlu.pretendyourexyzzy.api.crcast;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;

import com.gianlu.commonutils.analytics.AnalyticsApplication;
import com.gianlu.commonutils.misc.NamedThreadFactory;
import com.gianlu.commonutils.preferences.Prefs;
import com.gianlu.pretendyourexyzzy.PK;
import com.gianlu.pretendyourexyzzy.R;
import com.gianlu.pretendyourexyzzy.Utils;
import com.gianlu.pretendyourexyzzy.api.StatusCodeException;
import com.gianlu.pretendyourexyzzy.api.UserAgentInterceptor;
import com.gianlu.pretendyourexyzzy.customdecks.CustomDecksDatabase;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class CrCastApi {
    private static final CrCastApi instance = new CrCastApi();
    private static final String BASE_URL = "https://castapi.clrtd.com/";
    private static final String TAG = CrCastApi.class.getSimpleName();
    private final OkHttpClient client;
    private final ExecutorService executorService;

    private CrCastApi() {
        executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory("cr-cast-"));
        client = new OkHttpClient.Builder().addInterceptor(new UserAgentInterceptor()).build();
    }

    @SuppressLint("SimpleDateFormat")
    static long parseApiDate(@NonNull String text) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text);
            return date == null ? 0 : date.getTime();
        } catch (ParseException ex) {
            Log.e(TAG, "Failed parsing date: " + text, ex);
            return 0;
        }
    }

    @NonNull
    public static CrCastApi get() {
        return instance;
    }

    public static boolean hasCredentials() {
        return Prefs.has(PK.CR_CAST_TOKEN) || (Prefs.has(PK.CR_CAST_USER) && Prefs.has(PK.CR_CAST_PASSWORD));
    }

    @NonNull
    public static String getDeckUrl(@NonNull CrCastDeck deck) {
        return BASE_URL + "cc/decks/" + deck.watermark + "/all";
    }

    @NonNull
    @WorkerThread
    private JSONObject request(@NonNull String suffix) throws IOException, JSONException, CrCastException, NotSignedInException {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            String suffixWithToken;
            if (suffix.contains("{token}"))
                suffixWithToken = suffix.replace("{token}", getToken());
            else
                suffixWithToken = suffix;

            try (Response resp = client.newCall(new Request.Builder().get().url(BASE_URL + suffixWithToken).build()).execute()) {
                Log.v(TAG, suffix + " -> " + resp.code());
                if (resp.code() != 200) throw new StatusCodeException(resp);

                ResponseBody body = resp.body();
                if (body == null) throw new IOException("Missing body.");
                JSONObject json = new JSONObject(body.string());

                int errorCode;
                if ((errorCode = json.optInt("error", 0)) != 0) {
                    String msg = json.getString("message");
                    Log.d(TAG, suffix + " -> error: " + errorCode + " (" + msg + ")");
                    throw new CrCastException(errorCode, msg);
                }

                return json;
            } catch (JSONException | IOException ex) {
                lastEx = ex;
            } catch (CrCastException ex) {
                lastEx = ex;

                if (ex.code == ErrorCode.TOKEN_EXPIRED) {
                    Prefs.remove(PK.CR_CAST_TOKEN);
                    Log.d(TAG, "Token expired, renewing...");
                }
            }
        }

        if (lastEx instanceof IOException) throw (IOException) lastEx;
        else if (lastEx instanceof JSONException) throw (JSONException) lastEx;
        else throw (CrCastException) lastEx;
    }

    @NonNull
    @WorkerThread
    private String getToken() throws NotSignedInException {
        String token = Prefs.getString(PK.CR_CAST_TOKEN, null);
        if (token != null && !token.isEmpty()) return token;

        String user = Prefs.getString(PK.CR_CAST_USER, null);
        String pass = Prefs.getString(PK.CR_CAST_PASSWORD, null);
        if (user != null && pass != null) {
            try {
                return loginSync(user, pass);
            } catch (IOException | JSONException | CrCastException ex) {
                throw new CrCastApi.NotSignedInException("Failed login with username and hashed password.", ex);
            }
        }

        throw new NotSignedInException("No means to login.");
    }

    @WorkerThread
    @NonNull
    private String loginSync(@NonNull String username, @NonNull String hashedPassword) throws JSONException, IOException, CrCastException {
        try {
            JSONObject obj = request("user/token/?username=" + username + "&password=" + hashedPassword);
            Prefs.putString(PK.CR_CAST_USER, username);
            Prefs.putString(PK.CR_CAST_PASSWORD, hashedPassword);

            String token = obj.getString("token");
            Prefs.putString(PK.CR_CAST_TOKEN, token);
            return token;
        } catch (NotSignedInException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @NonNull
    public Task<Void> login(@NonNull String username, @NonNull String password) {
        return Tasks.call(executorService, () -> {
            byte[] hash = MessageDigest.getInstance("SHA512").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(new BigInteger(1, hash).toString(16));
            while (hex.length() < 32) hex.insert(0, "0");

            loginSync(username, hex.toString());
            AnalyticsApplication.sendAnalytics(Utils.ACTION_CR_CAST_LOGIN);
            return null;
        });
    }

    @NonNull
    public Task<CrCastUser> getUser() {
        return Tasks.call(executorService, () -> new CrCastUser(request("user/{token}")));
    }

    @NonNull
    public Task<List<CrCastDeck>> getDecks(@NonNull CustomDecksDatabase db) {
        return Tasks.call(executorService, () -> {
            JSONObject decks = request("user/decks/{token}").getJSONObject("decks");
            List<CrCastDeck> list = new ArrayList<>(decks.length());
            Iterator<String> iter = decks.keys();
            while (iter.hasNext())
                list.add(CrCastDeck.parse(decks.getJSONObject(iter.next()), db, false));

            decks = request("decks/fav/{token}").getJSONObject("decks");
            iter = decks.keys();
            while (iter.hasNext())
                list.add(CrCastDeck.parse(decks.getJSONObject(iter.next()), db, true));

            return list;
        });
    }

    @NonNull
    public Task<CrCastDeck> getDeck(@NonNull String deckCode, boolean fav, @NonNull CustomDecksDatabase db) {
        return Tasks.call(executorService, () -> CrCastDeck.parse(request((fav ? "deck/" : "user/decks/{token}/") + deckCode).getJSONObject("deck"), db, false));
    }

    public void logout() {
        Prefs.remove(PK.CR_CAST_TOKEN);
        Prefs.remove(PK.CR_CAST_USER);
        Prefs.remove(PK.CR_CAST_PASSWORD);
        AnalyticsApplication.sendAnalytics(Utils.ACTION_CR_CAST_LOGOUT);
    }

    public enum State {
        DECLINED(0), ACCEPTED(1), PENDING(2), UNPUBLISHED(3);

        public final int val;

        State(int val) {
            this.val = val;
        }

        @NonNull
        public static State parse(int val) {
            for (State state : values())
                if (val == state.val)
                    return state;

            throw new IllegalArgumentException("Unknown state: " + val);
        }

        @StringRes
        public int toFormal() {
            switch (this) {
                case DECLINED:
                    return R.string.declined;
                case ACCEPTED:
                    return R.string.accepted;
                case PENDING:
                    return R.string.pending;
                case UNPUBLISHED:
                    return R.string.unpublished;
                default:
                    throw new IllegalArgumentException("Unknown state: " + this);
            }
        }
    }

    public enum ErrorCode {
        OK(0), DB_ERROR(1), MISSING_PARAM(2), VALIDATION(3), ALREADY_EXISTS(4),
        NOT_FOUND(5), BANNED(6), NOT_ACTIVATED(7), NOT_AUTHORIZED(8), NOT_PERMITTED(9),
        TOKEN_EXPIRED(10);

        private final int val;

        ErrorCode(int val) {
            this.val = val;
        }

        @NonNull
        public static ErrorCode parse(int val) {
            for (ErrorCode code : values())
                if (val == code.val)
                    return code;

            throw new IllegalArgumentException("Unknown error code: " + val);
        }
    }

    public static class NotSignedInException extends Exception {
        NotSignedInException(@NonNull String message) {
            super(message);
        }

        NotSignedInException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }

    public static class CrCastException extends Exception {
        public final ErrorCode code;

        CrCastException(int errorCode, @NonNull String msg) {
            super(errorCode + ": " + msg);
            this.code = ErrorCode.parse(errorCode);
        }
    }
}
