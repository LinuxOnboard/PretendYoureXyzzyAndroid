package com.gianlu.pretendyourexyzzy.Main;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.gianlu.commonutils.AnalyticsApplication;
import com.gianlu.commonutils.RecyclerViewLayout;
import com.gianlu.commonutils.SuppressingLinearLayoutManager;
import com.gianlu.commonutils.Toaster;
import com.gianlu.pretendyourexyzzy.Adapters.ChatAdapter;
import com.gianlu.pretendyourexyzzy.NetIO.Models.PollMessage;
import com.gianlu.pretendyourexyzzy.NetIO.PYX;
import com.gianlu.pretendyourexyzzy.R;
import com.gianlu.pretendyourexyzzy.Utils;

import org.json.JSONException;

public class GlobalChatFragment extends Fragment implements ChatAdapter.IAdapter, PYX.IEventListener {
    private static final String POLL_TAG = "globalChat";
    private RecyclerViewLayout recyclerViewLayout;
    private ChatAdapter adapter;

    public static GlobalChatFragment getInstance() {
        return new GlobalChatFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout layout = (LinearLayout) inflater.inflate(R.layout.chat_fragment, container, false);
        recyclerViewLayout = layout.findViewById(R.id.chatFragment_recyclerViewLayout);
        recyclerViewLayout.disableSwipeRefresh();
        LinearLayoutManager llm = new SuppressingLinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);
        llm.setStackFromEnd(true);
        recyclerViewLayout.setLayoutManager(llm);
        recyclerViewLayout.getList().addItemDecoration(new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL));
        adapter = new ChatAdapter(getContext(), this);
        recyclerViewLayout.loadListData(adapter);

        final PYX pyx = PYX.get(getContext());

        final EditText message = layout.findViewById(R.id.chatFragment_message);
        final ImageButton send = layout.findViewById(R.id.chatFragment_send);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = message.getText().toString();
                if (msg.isEmpty()) return;

                message.setEnabled(false);
                send.setEnabled(false);
                pyx.sendMessage(msg, new PYX.ISuccess() {
                    @Override
                    public void onDone(PYX pyx) {
                        message.setText(null);
                        message.setEnabled(true);
                        send.setEnabled(true);

                        AnalyticsApplication.sendAnalytics(getContext(), Utils.ACTION_SENT_GLOBAL_MSG);
                    }

                    @Override
                    public void onException(Exception ex) {
                        Toaster.show(getActivity(), Utils.Messages.FAILED_SEND_MESSAGE, ex, new Runnable() {
                            @Override
                            public void run() {
                                message.setEnabled(true);
                                send.setEnabled(true);
                            }
                        });
                    }
                });
            }
        });

        pyx.getPollingThread().addListener(POLL_TAG, this);

        return layout;
    }

    public void scrollToTop() {
        recyclerViewLayout.getList().scrollToPosition(0);
    }

    @Override
    public void onItemCountChanged(int count) {
        if (count == 0) recyclerViewLayout.showMessage(R.string.noMessages, false);
        else recyclerViewLayout.showList();
    }

    @Override
    public void onPollMessage(PollMessage message) throws JSONException {
        if (!isAdded()) return;
        adapter.newMessage(message, null);
    }

    @Override
    public void onStoppedPolling() {
    }
}
