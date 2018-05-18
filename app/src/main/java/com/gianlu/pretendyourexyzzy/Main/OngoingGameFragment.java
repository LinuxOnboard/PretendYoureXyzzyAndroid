package com.gianlu.pretendyourexyzzy.Main;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.TextInputLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;

import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetSequence;
import com.gianlu.commonutils.Analytics.AnalyticsApplication;
import com.gianlu.commonutils.CommonUtils;
import com.gianlu.commonutils.Dialogs.DialogUtils;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.MessageLayout;
import com.gianlu.commonutils.NameValuePair;
import com.gianlu.commonutils.SuperTextView;
import com.gianlu.commonutils.Toaster;
import com.gianlu.pretendyourexyzzy.Adapters.PlayersAdapter;
import com.gianlu.pretendyourexyzzy.Cards.StarredDecksManager;
import com.gianlu.pretendyourexyzzy.Main.OngoingGame.BestGameManager;
import com.gianlu.pretendyourexyzzy.Main.OngoingGame.CardcastBottomSheet;
import com.gianlu.pretendyourexyzzy.NetIO.Cardcast;
import com.gianlu.pretendyourexyzzy.NetIO.LevelMismatchException;
import com.gianlu.pretendyourexyzzy.NetIO.Models.CardSet;
import com.gianlu.pretendyourexyzzy.NetIO.Models.FirstLoad;
import com.gianlu.pretendyourexyzzy.NetIO.Models.Game;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameInfo;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameInfoAndCards;
import com.gianlu.pretendyourexyzzy.NetIO.Pyx;
import com.gianlu.pretendyourexyzzy.NetIO.PyxRequests;
import com.gianlu.pretendyourexyzzy.NetIO.RegisteredPyx;
import com.gianlu.pretendyourexyzzy.R;
import com.gianlu.pretendyourexyzzy.TutorialManager;
import com.gianlu.pretendyourexyzzy.UserInfoDialog;
import com.gianlu.pretendyourexyzzy.Utils;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.HttpUrl;

public class OngoingGameFragment extends Fragment implements Pyx.OnResult<GameInfoAndCards>, BestGameManager.Listener, OngoingGameHelper.Listener, CardcastBottomSheet.DialogsHelper, PlayersAdapter.Listener {
    private OnLeftGame onLeftGame;
    private CoordinatorLayout layout;
    private ProgressBar loading;
    private LinearLayout container;
    private BestGameManager manager;
    private int gid;
    private RegisteredPyx pyx;
    private CardcastBottomSheet cardcastBottomSheet;
    private Cardcast cardcast;

    public static OngoingGameFragment getInstance(int gid, @Nullable SavedState savedState) {
        OngoingGameFragment fragment = new OngoingGameFragment();
        fragment.setHasOptionsMenu(true);
        fragment.setInitialSavedState(savedState);
        Bundle args = new Bundle();
        args.putSerializable("gid", gid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (context instanceof OnLeftGame)
            onLeftGame = (OnLeftGame) context;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) updateActivityTitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateActivityTitle();
    }

    @Override
    public void updateActivityTitle() {
        Activity activity = getActivity();
        if (manager != null && activity != null && isVisible())
            activity.setTitle(manager.gameInfo().game.host + " - " + getString(R.string.app_name));
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.ongoing_game, menu);
    }

    private void leaveGame() {
        pyx.request(PyxRequests.leaveGame(gid), new Pyx.OnSuccess() {
            @Override
            public void onDone() {
                if (onLeftGame != null) onLeftGame.onLeftGame();
            }

            @Override
            public void onException(@NonNull Exception ex) {
                Toaster.show(getActivity(), Utils.Messages.FAILED_LEAVING, ex);
            }
        });
    }

    private boolean amHost() {
        return getGame() != null && Objects.equals(getGame().host, pyx.user().nickname);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle savedInstanceState) {
        layout = (CoordinatorLayout) inflater.inflate(R.layout.fragment_ongoing_game, parent, false);
        loading = layout.findViewById(R.id.ongoingGame_loading);
        container = layout.findViewById(R.id.ongoingGame_container);

        Bundle args = getArguments();
        if (args == null || (gid = args.getInt("gid", -1)) == -1) {
            loading.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            MessageLayout.show(layout, R.string.failedLoading, R.drawable.ic_error_outline_black_48dp);
            return layout;
        }

        try {
            pyx = RegisteredPyx.get();
            cardcast = Cardcast.get(getContext());
        } catch (LevelMismatchException ex) {
            Logging.log(ex);
            loading.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            MessageLayout.show(layout, R.string.failedLoading, R.drawable.ic_error_outline_black_48dp);
            return layout;
        }

        cardcastBottomSheet = new CardcastBottomSheet(layout, gid, pyx, this, this);
        pyx.getGameInfoAndCards(gid, this);

        return layout;
    }

    @Override
    public void onDone(@NonNull GameInfoAndCards result) {
        if (manager == null && isAdded())
            manager = new BestGameManager(getActivity(), container, pyx, result, this, this);

        updateActivityTitle();

        loading.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);
        MessageLayout.hide(layout);

        if (getActivity() != null && TutorialManager.shouldShowHintFor(getContext(), TutorialManager.Discovery.CREATE_GAME) && isVisible() && Objects.equals(pyx.user().nickname, result.info.game.host)) {
            View options = getActivity().getWindow().getDecorView().findViewById(R.id.ongoingGame_options);
            if (options != null) {
                new TapTargetSequence(getActivity())
                        .target(Utils.tapTargetForView(options, R.string.tutorial_setupGame, R.string.tutorial_setupGame_desc))
                        .target(Utils.tapTargetForView(manager.getStartGameButton(), R.string.tutorial_startGame, R.string.tutorial_startGame_desc))
                        .listener(new TapTargetSequence.Listener() {
                            @Override
                            public void onSequenceFinish() {
                                TutorialManager.setHintShown(getContext(), TutorialManager.Discovery.CREATE_GAME);
                            }

                            @Override
                            public void onSequenceStep(TapTarget lastTarget, boolean targetClicked) {
                            }

                            @Override
                            public void onSequenceCanceled(TapTarget lastTarget) {
                            }
                        }).start();
            }
        }
    }

    @Override
    public void onException(@NonNull Exception ex) {
        Logging.log(ex);
        loading.setVisibility(View.GONE);
        container.setVisibility(View.GONE);
        if (isAdded())
            MessageLayout.show(layout, getString(R.string.failedLoading_reason, ex.getMessage()), R.drawable.ic_error_outline_black_48dp);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.ongoingGame_leave:
                leaveGame();
                return true;
            case R.id.ongoingGame_options:
                if (amHost() && manager.gameInfo().game.status == Game.Status.LOBBY)
                    editGameOptions();
                else showGameOptions();
                return true;
            case R.id.ongoingGame_spectators:
                showSpectators();
                return true;
            case R.id.ongoingGame_players:
                showPlayers();
                return true;
            case R.id.ongoingGame_share:
                shareGame();
                return true;
            case R.id.ongoingGame_cardcast:
                cardcastBottomSheet.expandAsLoading();
                pyx.request(PyxRequests.listCardcastDecks(gid, cardcast), new Pyx.OnResult<List<CardSet>>() {
                    @Override
                    public void onDone(@NonNull List<CardSet> result) {
                        if (cardcastBottomSheet != null) cardcastBottomSheet.expand(result);
                    }

                    @Override
                    public void onException(@NonNull Exception ex) {
                        Toaster.show(getActivity(), Utils.Messages.FAILED_LOADING, ex);
                    }
                });
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showPlayers() {
        if (manager == null || getContext() == null) return;
        RecyclerView recyclerView = new RecyclerView(getContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        recyclerView.addItemDecoration(new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(new PlayersAdapter(getContext(), manager.gameInfo().players, this));

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.playersLabel)
                .setView(recyclerView)
                .setPositiveButton(android.R.string.ok, null);

        DialogUtils.showDialog(getActivity(), builder);
    }

    private void shareGame() {
        if (getGame() == null) return;
        HttpUrl.Builder builder = pyx.server.url.newBuilder();
        builder.addPathSegment("game.jsp");

        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("game", String.valueOf(gid)));
        if (getGame().hasPassword())
            params.add(new NameValuePair("password", getGame().options.password));

        builder.fragment(CommonUtils.formQuery(params));

        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, builder.toString());
        startActivity(Intent.createChooser(i, "Share game..."));
    }

    private void showSpectators() {
        if (getGame() == null || getContext() == null) return;
        SuperTextView spectators = new SuperTextView(getContext(), R.string.spectatorsList, getGame().spectators.isEmpty() ? "none" : CommonUtils.join(getGame().spectators, ", "));
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        spectators.setPadding(padding, padding, padding, padding);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.spectatorsLabel)
                .setView(spectators)
                .setPositiveButton(android.R.string.ok, null);

        DialogUtils.showDialog(getActivity(), builder);
    }

    @SuppressLint("InflateParams")
    private void editGameOptions() { // TODO: FragmentDialog?
        if (!isAdded() || getGame() == null || getContext() == null) return;

        Game.Options options = getGame().options;
        final ScrollView layout = (ScrollView) LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_game_options, null, false);
        final TextInputLayout scoreLimit = layout.findViewById(R.id.editGameOptions_scoreLimit);
        CommonUtils.setText(scoreLimit, String.valueOf(options.scoreLimit));
        final TextInputLayout playerLimit = layout.findViewById(R.id.editGameOptions_playerLimit);
        CommonUtils.setText(playerLimit, String.valueOf(options.playersLimit));
        final TextInputLayout spectatorLimit = layout.findViewById(R.id.editGameOptions_spectatorLimit);
        CommonUtils.setText(spectatorLimit, String.valueOf(options.spectatorsLimit));
        final Spinner timerMultiplier = layout.findViewById(R.id.editGameOptions_timerMultiplier);
        timerMultiplier.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, Game.Options.VALID_TIMER_MULTIPLIERS));
        timerMultiplier.setSelection(Game.Options.timerMultiplierIndex(options.timerMultiplier));
        final TextInputLayout blankCards = layout.findViewById(R.id.editGameOptions_blankCards);
        CommonUtils.setText(blankCards, String.valueOf(options.blanksLimit));
        final TextInputLayout password = layout.findViewById(R.id.editGameOptions_password);
        CommonUtils.setText(password, options.password);
        final LinearLayout cardSets = layout.findViewById(R.id.editGameOptions_cardSets);
        cardSets.removeAllViews();
        for (CardSet set : pyx.firstLoad().cardSets) {
            CheckBox item = new CheckBox(getContext());
            item.setTag(set);
            item.setText(set.name);
            item.setChecked(getGame().options.cardSets.contains(set.id));
            cardSets.addView(item);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.editGameOptions)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.apply, null);

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View button) {
                        scoreLimit.setErrorEnabled(false);
                        playerLimit.setErrorEnabled(false);
                        spectatorLimit.setErrorEnabled(false);
                        blankCards.setErrorEnabled(false);
                        password.setErrorEnabled(false);

                        if (getContext() == null) return;

                        Game.Options newOptions;
                        try {
                            newOptions = Game.Options.validateAndCreate(timerMultiplier.getSelectedItem().toString(), CommonUtils.getText(spectatorLimit), CommonUtils.getText(playerLimit), CommonUtils.getText(scoreLimit), CommonUtils.getText(blankCards), cardSets, CommonUtils.getText(password));
                        } catch (Game.Options.InvalidFieldException ex) {
                            View view = layout.findViewById(ex.fieldId);
                            if (view != null && view instanceof TextInputLayout) {
                                if (ex.throwMessage == R.string.outOfRange)
                                    ((TextInputLayout) view).setError(getString(R.string.outOfRange, ex.min, ex.max));
                                else
                                    ((TextInputLayout) view).setError(getString(ex.throwMessage));
                            }
                            return;
                        }

                        DialogUtils.dismissDialog(getActivity());

                        try {
                            final ProgressDialog pd = DialogUtils.progressDialog(getContext(), R.string.loading);
                            DialogUtils.showDialog(getActivity(), pd);
                            pyx.request(PyxRequests.changeGameOptions(gid, newOptions), new Pyx.OnSuccess() {
                                @Override
                                public void onDone() {
                                    DialogUtils.dismissDialog(getActivity());
                                    Toaster.show(getActivity(), Utils.Messages.OPTIONS_CHANGED);
                                }

                                @Override
                                public void onException(@NonNull Exception ex) {
                                    DialogUtils.dismissDialog(getActivity());
                                    Toaster.show(getActivity(), Utils.Messages.FAILED_CHANGING_OPTIONS, ex);
                                }
                            });
                        } catch (JSONException ex) {
                            DialogUtils.dismissDialog(getActivity());
                            Toaster.show(getActivity(), Utils.Messages.FAILED_CHANGING_OPTIONS, ex);
                        }
                    }
                });
            }
        });

        DialogUtils.showDialog(getActivity(), dialog);
    }

    @SuppressLint("InflateParams")
    private void showGameOptions() {
        FirstLoad firstLoad = pyx.firstLoad();
        if (getContext() == null || getGame() == null) return;

        Game.Options options = getGame().options;
        ScrollView layout = (ScrollView) LayoutInflater.from(getContext()).inflate(R.layout.dialog_game_options, null, false);
        SuperTextView scoreLimit = layout.findViewById(R.id.gameOptions_scoreLimit);
        scoreLimit.setHtml(R.string.scoreLimit, options.scoreLimit);
        SuperTextView playerLimit = layout.findViewById(R.id.gameOptions_playerLimit);
        playerLimit.setHtml(R.string.playerLimit, options.playersLimit);
        SuperTextView spectatorLimit = layout.findViewById(R.id.gameOptions_spectatorLimit);
        spectatorLimit.setHtml(R.string.spectatorLimit, options.spectatorsLimit);
        SuperTextView timerMultiplier = layout.findViewById(R.id.gameOptions_timerMultiplier);
        timerMultiplier.setHtml(R.string.timerMultiplier, options.timerMultiplier);
        SuperTextView cardSets = layout.findViewById(R.id.gameOptions_cardSets);
        cardSets.setHtml(R.string.cardSets, options.cardSets.isEmpty() ? "<i>none</i>" : CommonUtils.join(firstLoad.createCardSetNamesList(options.cardSets), ", "));
        SuperTextView blankCards = layout.findViewById(R.id.gameOptions_blankCards);
        blankCards.setHtml(R.string.blankCards, options.blanksLimit);
        SuperTextView password = layout.findViewById(R.id.gameOptions_password);
        if (options.password == null || options.password.isEmpty())
            password.setVisibility(View.GONE);
        else password.setHtml(R.string.password, options.password);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.gameOptions)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null);

        DialogUtils.showDialog(getActivity(), builder);
    }

    @Nullable
    private Game getGame() {
        return manager == null ? null : manager.gameInfo().game;
    }

    @Override
    public void addCardcastDeck(String code) {
        if (code == null || code.length() != 5) {
            Toaster.show(getActivity(), Utils.Messages.INVALID_CARDCAST_CODE, code);
            return;
        }

        pyx.addCardcastDeckAndList(gid, code, cardcast, new Pyx.OnResult<List<CardSet>>() {
            @Override
            public void onDone(@NonNull List<CardSet> result) {
                Toaster.show(getActivity(), Utils.Messages.CARDCAST_ADDED);
                AnalyticsApplication.sendAnalytics(getContext(), Utils.ACTION_ADDED_CARDCAST);

                if (cardcastBottomSheet != null && cardcastBottomSheet.isExpanded())
                    cardcastBottomSheet.update(result);
            }

            @Override
            public void onException(@NonNull Exception ex) {
                Toaster.show(getActivity(), Utils.Messages.FAILED_ADDING_CARDCAST, ex);
            }
        });
    }

    public void onBackPressed() {
        if (isVisible() && cardcastBottomSheet != null && cardcastBottomSheet.isExpanded()) {
            cardcastBottomSheet.collapse();
            return;
        }

        if (getContext() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.leaveGame)
                .setMessage(R.string.leaveGame_confirm)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        leaveGame();
                    }
                })
                .setNegativeButton(android.R.string.no, null);

        DialogUtils.showDialog(getActivity(), builder);
    }

    @Override
    public void shouldLeaveGame() {
        leaveGame();
    }

    @Override
    public void showDialog(AlertDialog.Builder builder) {
        DialogUtils.showDialog(getActivity(), builder);
    }

    @Override
    public void showDialog(Dialog dialog) {
        DialogUtils.showDialog(getActivity(), dialog);
    }

    @Override
    public boolean canModifyCardcastDecks() {
        return amHost() && getGame() != null && getGame().status == Game.Status.LOBBY;
    }

    @Override
    public void addCardcastStarredDecks() {
        List<StarredDecksManager.StarredDeck> starredDecks = StarredDecksManager.loadDecks(getContext());
        if (starredDecks.isEmpty()) {
            Toaster.show(getActivity(), Utils.Messages.NO_STARRED_DECKS);
            return;
        }

        List<String> codes = new ArrayList<>();
        for (StarredDecksManager.StarredDeck deck : starredDecks)
            codes.add(deck.code);

        pyx.addCardcastDecksAndList(gid, codes, cardcast, new Pyx.OnResult<List<CardSet>>() {
            @Override
            public void onDone(@NonNull List<CardSet> result) {
                Toaster.show(getActivity(), Utils.Messages.ADDED_STARRED_DECKS);
                AnalyticsApplication.sendAnalytics(getContext(), Utils.ACTION_ADDED_CARDCAST);

                if (cardcastBottomSheet != null && cardcastBottomSheet.isExpanded())
                    cardcastBottomSheet.update(result);
            }

            @Override
            public void onException(@NonNull Exception ex) {
                if (ex instanceof RegisteredPyx.PartialCardcastAddFail) {
                    Toaster.show(getActivity(), Utils.Messages.PARTIAL_ADD_STARRED_DECKS_FAILED, ((RegisteredPyx.PartialCardcastAddFail) ex).getCodes());
                    return;
                }

                Toaster.show(getActivity(), Utils.Messages.FAILED_ADDING_CARDCAST, ex);
            }
        });
    }

    @Override
    public void onPlayerSelected(@NonNull GameInfo.Player player) {
        final FragmentActivity activity = getActivity();
        if (activity != null) UserInfoDialog.loadAndShow(pyx, activity, player.name);
    }
}
