package com.gianlu.pretendyourexyzzy.game;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.text.HtmlCompat;

import com.gianlu.commonutils.dialogs.DialogUtils;
import com.gianlu.commonutils.ui.Toaster;
import com.gianlu.pretendyourexyzzy.GPGamesHelper;
import com.gianlu.pretendyourexyzzy.R;
import com.gianlu.pretendyourexyzzy.ThisApplication;
import com.gianlu.pretendyourexyzzy.Utils;
import com.gianlu.pretendyourexyzzy.api.Pyx;
import com.gianlu.pretendyourexyzzy.api.PyxException;
import com.gianlu.pretendyourexyzzy.api.PyxRequests;
import com.gianlu.pretendyourexyzzy.api.RegisteredPyx;
import com.gianlu.pretendyourexyzzy.api.models.CardsGroup;
import com.gianlu.pretendyourexyzzy.api.models.Deck;
import com.gianlu.pretendyourexyzzy.api.models.Game;
import com.gianlu.pretendyourexyzzy.api.models.GameInfo;
import com.gianlu.pretendyourexyzzy.api.models.GamePermalink;
import com.gianlu.pretendyourexyzzy.api.models.PollMessage;
import com.gianlu.pretendyourexyzzy.api.models.cards.BaseCard;
import com.gianlu.pretendyourexyzzy.api.models.cards.GameCard;
import com.gianlu.pretendyourexyzzy.dialogs.Dialogs;
import com.gianlu.pretendyourexyzzy.dialogs.NewEditGameOptionsDialog;
import com.gianlu.pretendyourexyzzy.dialogs.NewUserInfoDialog;
import com.gianlu.pretendyourexyzzy.dialogs.NewViewGameOptionsDialog;
import com.gianlu.pretendyourexyzzy.starred.StarredCardsDatabase;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import xyz.gianlu.pyxoverloaded.OverloadedApi;

import static com.gianlu.commonutils.CommonUtils.optString;

/**
 * The class handling all the game logic. Storing data is delegated to {@link GameData} and managing the UI to {@link GameUi}.
 */
public class AnotherGameManager implements Pyx.OnEventListener, GameData.Listener, GameUi.Listener {
    private static final String TAG = AnotherGameManager.class.getSimpleName();
    private final GamePermalink permalink;
    private final RegisteredPyx pyx;
    private final GameUi ui;
    private final GameData gameData;
    private final Listener listener;
    private final int gid;
    private final Context context;
    private final StarredCardsDatabase starredDb;
    private final ArrayList<Deck> customDecks = new ArrayList<>(10);

    public AnotherGameManager(@NotNull Context context, @NonNull GamePermalink permalink, @NonNull RegisteredPyx pyx, @NonNull GameUi ui, @NonNull Listener listener) {
        this.context = context;
        this.permalink = permalink;
        this.pyx = pyx;
        this.gid = permalink.gid;
        this.ui = ui;
        this.ui.setListener(this);
        this.starredDb = StarredCardsDatabase.get(context);
        this.gameData = new GameData(pyx.user().nickname, this);
        this.listener = listener;
    }

    //region Events handling
    @Override
    public void onPollMessage(@NonNull PollMessage msg) throws JSONException {
        Log.v(TAG, msg.event + " -> " + msg.obj.toString());

        switch (msg.event) {
            case GAME_JUDGE_LEFT:
                judgeLeft(msg.obj.getInt("i"));
                break;
            case GAME_JUDGE_SKIPPED:
                judgeSkipped();
                break;
            case GAME_OPTIONS_CHANGED:
                gameData.updateGame(new Game(msg.obj.getJSONObject("gi")));
                break;
            case GAME_PLAYER_INFO_CHANGE:
                gameData.playerChange(new GameInfo.Player(msg.obj.getJSONObject("pi")));
                break;
            case GAME_PLAYER_KICKED_IDLE:
                event(UiEvent.PLAYER_KICKED, msg.obj.getString("n"));
                break;
            case GAME_PLAYER_LEAVE:
            case GAME_PLAYER_JOIN:
                if (!msg.obj.getString("n").equals(gameData.me)) updateGameInfo();
                break;
            case GAME_PLAYER_SKIPPED:
                event(UiEvent.PLAYER_SKIPPED, msg.obj.getString("n"));
                break;
            case GAME_ROUND_COMPLETE:
                roundComplete(msg.obj.getInt("WC"), msg.obj.getString("rw"), msg.obj.getInt("i"), optString(msg.obj, "rP"));
                break;
            case GAME_STATE_CHANGE:
                gameStateChange(msg);
                break;
            case HAND_DEAL:
                dealCards(GameCard.list(msg.obj.getJSONArray("h")));
                break;
            case KICKED_FROM_GAME_IDLE:
                destroy();
                break;
            case HURRY_UP:
                event(UiEvent.HURRY_UP);
                break;
            case GAME_SPECTATOR_JOIN:
                gameData.spectatorJoin(msg.obj.getString("n"));
                break;
            case GAME_SPECTATOR_LEAVE:
                gameData.spectatorLeave(msg.obj.getString("n"));
                break;
            case ADD_CR_CAST_CARDSET:
            case ADD_CARDSET:
                customDecks.add(new Deck(msg.obj.getJSONObject("cdi"), true));
                break;
            case REMOVE_CR_CAST_CARDSET:
            case REMOVE_CARDSET:
                customDecks.remove(new Deck(msg.obj.getJSONObject("cdi"), true));
                break;
            case GAME_LIST_REFRESH:
            case GAME_BLACK_RESHUFFLE:
            case GAME_WHITE_RESHUFFLE:
            case KICKED:
            case BANNED:
            case CHAT:
            case PLAYER_LEAVE:
            case NEW_PLAYER:
            case NOOP:
                break;
        }
    }

    private void judgeSkipped() {
        if (gameData.judge != null)
            event(UiEvent.JUDGE_SKIPPED, gameData.judge);
    }

    private void judgeLeft(int intermission) {
        if (gameData.judge != null)
            event(UiEvent.JUDGE_LEFT, gameData.judge);

        ui.clearTable();
        ui.showTable(false);
        ui.setBlackCard(null);

        ui.countFrom(intermission);
    }

    private void roundComplete(int winnerCard, @NonNull String roundWinner, int intermission, @Nullable String lastRoundPermalink) {
        ui.notifyWinnerCard(winnerCard);
        ui.countFrom(intermission);

        if (roundWinner.equals(gameData.me)) {
            GPGamesHelper.incrementEvent(context, 1, GPGamesHelper.EVENT_ROUNDS_WON);
            GPGamesHelper.incrementAchievement(context, 1, GPGamesHelper.ACHS_WIN_ROUNDS);
            event(UiEvent.YOU_ROUND_WINNER);
        } else {
            event(UiEvent.ROUND_WINNER, roundWinner);
        }

        gameData.lastRoundPermalink = lastRoundPermalink;
        if (!gameData.amSpectator()) {
            GPGamesHelper.incrementEvent(context, 1, GPGamesHelper.EVENT_ROUNDS_PLAYED);
            GPGamesHelper.updateWinRate(context);
        }
    }

    private void dealCards(List<BaseCard> cards) {
        ui.addHand(cards);
    }

    private void gameStateChange(@NonNull PollMessage msg) throws JSONException {
        Game.Status status = Game.Status.parse(msg.obj.getString("gs"));
        gameData.updateStatus(status);
        switch (status) {
            case JUDGING:
                ui.hideLobby();
                ui.countFrom(msg.obj.getInt("Pt"));
                ui.setTable(CardsGroup.list(msg.obj.getJSONArray("wc")), ui.blackCard());
                ui.showTable(gameData.amJudge());
                break;
            case PLAYING:
                ui.hideLobby();
                ui.countFrom(msg.obj.getInt("Pt"));
                ui.clearTable();
                ui.setBlackCard(GameCard.parse(msg.obj.getJSONObject("bc")));
                updateGameInfo();

                if (gameData.amHost()) {
                    boolean hasCustomDeck = false;
                    for (int deckId : gameData.options.cardSets) {
                        if (deckId < 0) {
                            hasCustomDeck = true; // Custom decks ids are always negative
                            break;
                        }
                    }

                    if (hasCustomDeck)
                        GPGamesHelper.unlockAchievement(context, GPGamesHelper.ACH_CUSTOM_DECK);
                }
                break;
            case LOBBY:
                gameData.resetToIdleAndHost();

                event(UiEvent.WAITING_FOR_START);
                ui.showLobby();
                break;
            case DEALING:
            case ROUND_OVER:
                break;
        }

        permalink.gamePermalink = optString(msg.obj, "gp");
    }

    @Override
    public void ourPlayerChanged(@NonNull GameInfo.Player player, @Nullable GameInfo.PlayerStatus oldStatus) {
        ui.startGameVisible(player.status == GameInfo.PlayerStatus.HOST);

        switch (player.status) {
            case HOST:
                event(UiEvent.YOU_GAME_HOST);
                break;
            case IDLE:
                if (oldStatus == GameInfo.PlayerStatus.PLAYING) {
                    if (gameData.status != Game.Status.JUDGING)
                        event(UiEvent.WAITING_FOR_OTHER_PLAYERS);
                } else if (oldStatus == null) {
                    if (gameData.status == Game.Status.LOBBY) event(UiEvent.WAITING_FOR_START);
                    else event(UiEvent.WAITING_FOR_ROUND_TO_END);
                }

                ui.showTable(false);
                break;
            case JUDGE:
                if (oldStatus != GameInfo.PlayerStatus.JUDGING) event(UiEvent.YOU_JUDGE);
                ui.showTable(false);
                break;
            case JUDGING:
                event(UiEvent.SELECT_WINNING_CARD);
                ui.showTable(true);
                break;
            case PLAYING:
                BaseCard bc = ui.blackCard();
                if (bc != null)
                    event(UiEvent.PICK_CARDS, context.getResources().getQuantityString(R.plurals.game_cards, bc.numPick(), bc.numPick()));
                ui.showHand(true);
                break;
            case WINNER:
                event(UiEvent.YOU_GAME_WINNER);
                GPGamesHelper.incrementEvent(context, 1, GPGamesHelper.EVENT_GAMES_WON);
                break;
            case SPECTATOR:
                break;
        }
    }

    @Override
    public void anyPlayerChanged(@NonNull GameInfo.Player player, @Nullable GameInfo.PlayerStatus oldStatus) {
    }

    @Override
    public void notOutPlayerChanged(@NonNull GameInfo.Player player, @Nullable GameInfo.PlayerStatus oldStatus) {
        if (player.status == GameInfo.PlayerStatus.WINNER)
            event(UiEvent.GAME_WINNER, player.name);

        if (player.status == GameInfo.PlayerStatus.JUDGING && gameData.status == Game.Status.JUDGING)
            event(UiEvent.IS_JUDGING, player.name);

        if (oldStatus == GameInfo.PlayerStatus.PLAYING && player.status == GameInfo.PlayerStatus.IDLE && gameData.status == Game.Status.PLAYING)
            ui.addBlankCardTable();
    }

    @Override
    public void playerIsSpectator() {
        ui.showTable(false);
        event(UiEvent.SPECTATOR_TEXT);
    }
    //endregion

    //region Internals
    private void updateGameInfo() {
        pyx.request(PyxRequests.getGameInfo(gid))
                .addOnSuccessListener(result -> {
                    gameData.update(result, null, null);

                    if (gameData.amHost()) {
                        int players = result.players.size() - 1; // Do not include ourselves
                        GPGamesHelper.achievementSteps(context, players, GPGamesHelper.ACHS_PEOPLE_GAME);
                    }

                    if (gameData.amSpectator() && result.players.isEmpty())
                        listener.justLeaveGame();
                })
                .addOnFailureListener(ex -> {
                    Log.e(TAG, "Failed getting game info.", ex);
                    listener.showToast(Toaster.build().message(R.string.failedLoading));

                    if (ex instanceof PyxException && ((PyxException) ex).errorCode.equals("ig"))
                        listener.justLeaveGame();
                });
    }

    /**
     * @return Whether the exception has been handled
     */
    private boolean handleStartGameException(@NonNull PyxException ex) {
        if (Objects.equals(ex.errorCode, "nep")) {
            Toaster.with(context).message(R.string.notEnoughPlayers).show();
            return true;
        } else if (Objects.equals(ex.errorCode, "nec")) {
            try {
                listener.showDialog(Dialogs.notEnoughCards(context, ex));
                return true;
            } catch (JSONException exx) {
                Log.e(TAG, "Failed parsing JSON.", exx);
                return false;
            }
        } else {
            return false;
        }
    }

    private void judgeCardInternal(@NonNull GameCard card) {
        listener.showProgress(R.string.loading);
        pyx.request(PyxRequests.judgeCard(gid, card.id))
                .addOnSuccessListener(aVoid -> {
                    ThisApplication.sendAnalytics(Utils.ACTION_JUDGE_CARD);
                    GPGamesHelper.incrementEvent(context, 1, GPGamesHelper.EVENT_ROUNDS_JUDGED);
                    listener.dismissDialog();
                })
                .addOnFailureListener(ex -> {
                    listener.dismissDialog();

                    if (ex instanceof PyxException) {
                        if (((PyxException) ex).errorCode.equals("nj")) {
                            event(UiEvent.NOT_YOUR_TURN);
                            return;
                        }
                    }

                    Log.e(TAG, "Failed judging.", ex);
                    listener.showToast(Toaster.build().message(R.string.failedJudging));
                });
    }

    private void playCardInternal(@NonNull GameCard card, @Nullable String text) {
        listener.showProgress(R.string.loading);
        pyx.request(PyxRequests.playCard(gid, card.id, text))
                .addOnSuccessListener(aVoid -> {
                    ui.removeHand(card);
                    ui.addTable(card, ui.blackCard());

                    ThisApplication.sendAnalytics(text == null ? Utils.ACTION_PLAY_CARD : Utils.ACTION_PLAY_CUSTOM_CARD);
                    GPGamesHelper.incrementEvent(context, 1, GPGamesHelper.EVENT_CARDS_PLAYED);
                    listener.dismissDialog();
                })
                .addOnFailureListener(ex -> {
                    listener.dismissDialog();

                    if (ex instanceof PyxException) {
                        if (((PyxException) ex).errorCode.equals("nyt")) {
                            event(UiEvent.NOT_YOUR_TURN);
                            return;
                        } else if (((PyxException) ex).errorCode.equals("dnhc")) {
                            ui.removeHand(card);
                        }
                    }

                    Log.e(TAG, "Failed playing.", ex);
                    listener.showToast(Toaster.build().message(R.string.failedPlayingCard));
                });
    }
    //endregion

    //region Public methods
    public void reset() {
        pyx.polling().removeListener(this);
        ui.setListener(null);
        ui.resetTimer();
    }

    public void destroy() {
        reset();
        listener.justLeaveGame();
    }

    public void begin() {
        pyx.getGameInfoAndCards(gid)
                .addOnSuccessListener(result -> {
                    gameData.update(result.info, result.cards, ui);
                    listener.onGameLoaded();
                    pyx.polling().addListener(this);
                })
                .addOnFailureListener(listener::onFailedLoadingGame);

        pyx.request(PyxRequests.listCrCastDecks(gid))
                .addOnSuccessListener(customDecks::addAll)
                .addOnFailureListener(ex -> Log.e(TAG, "Failed getting game CrCast decks.", ex));

        pyx.request(PyxRequests.listCustomDecks(gid))
                .addOnSuccessListener(customDecks::addAll)
                .addOnFailureListener(ex -> Log.e(TAG, "Failed getting game custom decks.", ex));
    }

    public void startGame() {
        pyx.request(PyxRequests.startGame(gid))
                .addOnSuccessListener(aVoid -> Toaster.with(context).message(R.string.gameStarted).show())
                .addOnFailureListener(ex -> {
                    Log.e(TAG, "Failed starting game.", ex);
                    if (!(ex instanceof PyxException) || !handleStartGameException((PyxException) ex))
                        Toaster.with(context).message(R.string.failedStartGame).show();
                });
    }
    //endregion

    //region Listener callbacks
    public void onCardSelected(@NonNull BaseCard card) {
        if (!(card instanceof GameCard))
            return;

        if (gameData.amJudge()) {
            listener.showDialog(Dialogs.confirmation(context, R.string.areYouSureJudgeCard, () -> judgeCardInternal((GameCard) card)));
        } else {
            if (((GameCard) card).writeIn) {
                listener.showDialog(Dialogs.askText(context, text -> playCardInternal((GameCard) card, text)));
            } else {
                listener.showDialog(Dialogs.confirmation(context, R.string.areYouSurePlayCard, () -> playCardInternal((GameCard) card, null)));
            }
        }
    }

    @Override
    public void showOptions(boolean goToCustomDecks) {
        if (gameData.options == null)
            return;

        if (amHost() && gameData.status == Game.Status.LOBBY)
            listener.showDialog(NewEditGameOptionsDialog.get(gid, gameData.options, customDecks, goToCustomDecks));
        else
            listener.showDialog(NewViewGameOptionsDialog.get(gameData.options, customDecks));
    }

    @Override
    public void onPlayerSelected(@NonNull String name) {
        if (name.equals(gameData.me))
            listener.showToast(Toaster.build().message(R.string.thisIsYou));
        else
            listener.showDialog(NewUserInfoDialog.get(name, true, OverloadedApi.get().isOverloadedUserOnServerCached(name)));
    }

    @Override
    public boolean onStarCard(@NotNull CardsGroup group) {
        BaseCard bc = ui.blackCard();
        if (bc != null) return starredDb.putCard((GameCard) bc, group);
        else return false;
    }
    //endregion

    //region Getters
    @Nullable
    public String getLastRoundMetricsId() {
        if (gameData.lastRoundPermalink == null) return null;
        String[] split = gameData.lastRoundPermalink.split("/");
        return split[split.length - 1];
    }

    @Override
    public boolean amHost() {
        return gameData.amHost();
    }

    @Override
    public boolean isLobby() {
        return gameData.status == Game.Status.LOBBY;
    }

    @NonNull
    public List<GameInfo.Player> players() {
        return Collections.unmodifiableList(gameData.players);
    }

    @NonNull
    public String host() {
        return gameData.host;
    }
    //endregion

    private void event(@NonNull UiEvent ev, Object... args) {
        if (ev == UiEvent.SPECTATOR_TEXT || !gameData.amSpectator()) {
            CharSequence textStr = ev.textStr(context, args);
            if (textStr != null) ui.setInstructions(textStr);
        }

        String toastStr = ev.toastStr(context, args);
        if (toastStr != null) listener.showToast(Toaster.build().message(toastStr));
    }

    private enum UiEvent {
        YOU_JUDGE(R.string.game_youJudge, Kind.TEXT, false),
        SELECT_WINNING_CARD(R.string.game_selectWinningCard, Kind.TEXT, false),
        YOU_ROUND_WINNER(R.string.game_youRoundWinner_long, R.string.game_youRoundWinner_short, false),
        SPECTATOR_TEXT(R.string.game_spectator, Kind.TEXT, false),
        YOU_GAME_HOST(R.string.game_youGameHost, Kind.TEXT, false),
        WAITING_FOR_ROUND_TO_END(R.string.game_waitingForRoundToEnd, Kind.TEXT, false),
        WAITING_FOR_START(R.string.game_waitingForStart, Kind.TEXT, false),
        JUDGE_LEFT(R.string.game_judgeLeft_long, R.string.game_judgeLeft_short, true),
        IS_JUDGING(R.string.game_isJudging, Kind.TEXT, true),
        ROUND_WINNER(R.string.game_roundWinner_long, R.string.game_roundWinner_short, true),
        WAITING_FOR_OTHER_PLAYERS(R.string.game_waitingForPlayers, Kind.TEXT, false),
        PLAYER_SKIPPED(R.string.game_playerSkipped, Kind.TOAST, false),
        PICK_CARDS(R.string.game_pickCards, Kind.TEXT, false),
        JUDGE_SKIPPED(R.string.game_judgeSkipped, Kind.TOAST, false),
        GAME_WINNER(R.string.game_gameWinner_long, R.string.game_gameWinner_short, true),
        YOU_GAME_WINNER(R.string.game_youGameWinner_long, R.string.game_youGameWinner_short, false),
        NOT_YOUR_TURN(R.string.game_notYourTurn, Kind.TOAST, false),
        HURRY_UP(R.string.hurryUp, Kind.TOAST, false),
        PLAYER_KICKED(R.string.game_playerKickedIdle, Kind.TOAST, false);

        private final int toast;
        private final int text;
        private final Kind kind;
        private final boolean colored;

        UiEvent(@StringRes int text, Kind kind, boolean colored) {
            this.text = this.toast = text;
            this.kind = kind;
            this.colored = colored;
        }

        UiEvent(@StringRes int text, @StringRes int toast, boolean colored) {
            this.toast = toast;
            this.text = text;
            this.colored = colored;
            this.kind = Kind.BOTH;
        }

        @Nullable
        public CharSequence textStr(@NonNull Context context, Object... args) {
            if (kind == Kind.TOAST) return null;

            String formatStr = null;
            if (colored && args.length == 1)
                formatStr = "<font color='#00B0FF'>" + args[0] + "</font>";

            return HtmlCompat.fromHtml(context.getString(text, formatStr == null ? args : new String[]{formatStr}), HtmlCompat.FROM_HTML_MODE_LEGACY);
        }

        @Nullable
        public String toastStr(@NonNull Context context, Object... args) {
            if (kind == Kind.TEXT) return null;
            else if (kind == Kind.TOAST) return context.getString(toast, args);
            else return context.getString(text, args);
        }

        public enum Kind {
            TOAST, TEXT, BOTH
        }
    }

    public interface Listener extends DialogUtils.ShowStuffInterface {
        void onGameLoaded();

        void onFailedLoadingGame(@NonNull Exception ex);

        void justLeaveGame();
    }
}
