package com.gianlu.pretendyourexyzzy.overloaded.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.gianlu.commonutils.adapters.NotFilterable;
import com.gianlu.commonutils.adapters.OrderedRecyclerViewAdapter;
import com.gianlu.commonutils.dialogs.FragmentWithDialog;
import com.gianlu.commonutils.misc.RecyclerMessageView;
import com.gianlu.pretendyourexyzzy.R;
import com.gianlu.pretendyourexyzzy.overloaded.ChatBottomSheet;

import java.util.Comparator;
import java.util.List;

import xyz.gianlu.pyxoverloaded.OverloadedApi;
import xyz.gianlu.pyxoverloaded.OverloadedChatApi;
import xyz.gianlu.pyxoverloaded.callback.ChatsCallback;
import xyz.gianlu.pyxoverloaded.model.Chat;
import xyz.gianlu.pyxoverloaded.model.PlainChatMessage;

public class ChatsFragment extends FragmentWithDialog implements OverloadedApi.EventListener {
    private static final String TAG = ChatsFragment.class.getSimpleName();
    private ChatBottomSheet lastChatSheet;
    private ChatsAdapter adapter;
    private RecyclerMessageView rmv;
    private OverloadedChatApi chatApi;

    @NonNull
    public static ChatsFragment get(@NonNull Context context) {
        ChatsFragment fragment = new ChatsFragment();
        Bundle args = new Bundle();
        args.putString("title", context.getString(R.string.chats));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onEvent(@NonNull OverloadedApi.Event event) {
        if (event.type == OverloadedApi.Event.Type.CHAT_MESSAGE && event.obj != null) {
            int chatId = ((PlainChatMessage) event.obj).chatId;
            if (adapter != null) adapter.refresh(chatId);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == ChatBottomSheet.RC_REFRESH_LIST) {
            if (adapter != null && lastChatSheet != null && lastChatSheet.isAdded())
                adapter.refresh(lastChatSheet.getSetupPayload().id);
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rmv = new RecyclerMessageView(requireContext());
        rmv.linearLayoutManager(RecyclerView.VERTICAL, false);
        rmv.dividerDecoration(RecyclerView.VERTICAL);
        rmv.startLoading();

        chatApi = OverloadedApi.chat(requireContext());
        chatApi.listChats(getActivity(), new ChatsCallback() {
            @Override
            public void onRemoteChats(@NonNull List<Chat> chats) {
                if (adapter != null) adapter.itemsChanged(chats);
                else rmv.loadListData(adapter = new ChatsAdapter(requireContext(), chats), false);
            }

            @Override
            public void onLocalChats(@NonNull List<Chat> chats) {
                rmv.loadListData(adapter = new ChatsAdapter(requireContext(), chats), false);
            }

            @Override
            public void onFailed(@NonNull Exception ex) {
                Log.e(TAG, "Failed getting chats.", ex);
                if (ex instanceof OverloadedApi.MaintenanceException)
                    rmv.showError(((OverloadedApi.MaintenanceException) ex).messageString(requireContext()));
                else
                    rmv.showError(R.string.failedLoading);
            }
        });

        OverloadedApi.get().addEventListener(this);
        return rmv;
    }

    private class ChatsAdapter extends OrderedRecyclerViewAdapter<ChatsAdapter.ViewHolder, Chat, Void, NotFilterable> {
        private final LayoutInflater inflater;

        ChatsAdapter(@NonNull Context context, @NonNull List<Chat> chats) {
            super(chats, null);
            inflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(parent);
        }

        void refresh(int chatId) {
            for (int i = 0; i < objs.size(); i++) {
                Chat chat = objs.get(i);
                if (chat.id == chatId) {
                    notifyItemChanged(i);
                    return;
                }
            }

            chatApi.listChats(null, new ChatsCallback() {
                @Override
                public void onRemoteChats(@NonNull List<Chat> chats) {
                    for (Chat chat : chats) {
                        if (chat.id == chatId) {
                            itemChangedOrAdded(chat);
                            return;
                        }
                    }
                }

                @Override
                public void onLocalChats(@NonNull List<Chat> chats) {
                }

                @Override
                public void onFailed(@NonNull Exception ex) {
                    Log.e(TAG, "Failed getting chats to refresh adapter.", ex);
                }
            });
        }

        @Override
        protected boolean matchQuery(@NonNull Chat item, @Nullable String query) {
            return true;
        }

        @Override
        protected void onSetupViewHolder(@NonNull ViewHolder holder, int position, @NonNull Chat payload) {
            Chat chat = objs.get(position);
            holder.username.setText(chat.getOtherUsername());

            PlainChatMessage lastMsg = chatApi.getLastMessage(chat.id);
            if (lastMsg == null) {
                holder.lastMsg.setVisibility(View.GONE);
            } else {
                holder.lastMsg.setVisibility(View.VISIBLE);
                holder.lastMsg.setText(lastMsg.text);
            }

            int unread = chatApi.countSinceLastSeen(chat.id);
            if (unread == 0) {
                holder.unread.setVisibility(View.GONE);
            } else {
                holder.unread.setVisibility(View.VISIBLE);
                holder.unread.setText(String.valueOf(unread));
            }

            holder.itemView.setOnClickListener(v -> {
                lastChatSheet = new ChatBottomSheet();
                lastChatSheet.show(ChatsFragment.this, chat);
            });
        }

        @Override
        protected void onUpdateViewHolder(@NonNull ViewHolder holder, int position, @NonNull Chat payload) {
            // No update performed
        }

        @Override
        protected void shouldUpdateItemCount(int count) {
            if (count == 0) rmv.showInfo(R.string.overloaded_noChats);
            else rmv.showList();
        }

        @NonNull
        @Override
        public Comparator<Chat> getComparatorFor(Void sorting) {
            return (o1, o2) -> {
                PlainChatMessage m1 = chatApi.getLastMessage(o1.id);
                if (m1 == null) return 0;

                PlainChatMessage m2 = chatApi.getLastMessage(o2.id);
                if (m2 == null) return 0;

                return (int) (m2.timestamp - m1.timestamp);
            };
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView username;
            final TextView lastMsg;
            final TextView unread;

            ViewHolder(@NonNull ViewGroup parent) {
                super(inflater.inflate(R.layout.item_overloaded_chat, parent, false));

                username = itemView.findViewById(R.id.overloadedChatItem_user);
                unread = itemView.findViewById(R.id.overloadedChatItem_unread);
                lastMsg = itemView.findViewById(R.id.overloadedChatItem_lastMsg);
            }
        }
    }
}