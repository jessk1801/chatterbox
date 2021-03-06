package com.gonevertical.chatterbox.room;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.firebase.ui.database.FirebaseRecyclerAdapter;
import com.gonevertical.chatterbox.AppConstant;
import com.gonevertical.chatterbox.BaseActivity;
import com.gonevertical.chatterbox.MainActivity;
import com.gonevertical.chatterbox.R;
import com.gonevertical.chatterbox.chat.ChatsActivity;
import com.gonevertical.chatterbox.group.GroupsActivity;
import com.google.android.gms.appinvite.AppInviteInvitation;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

public class RoomsActivity extends BaseActivity implements EditRoomDialog.EditRoomDialogListener, DeleteRoomDialog.DeleteDialogListener {

    public static Intent createIntent(Context context, String groupKey) {
        Intent in = new Intent();
        in.setClass(context, RoomsActivity.class);
        in.putExtra(AppConstant.GROUP_KEY, groupKey);
        return in;
    }

    private static final String TAG = RoomsActivity.class.getSimpleName();
    private static final int REQUEST_INVITE = 1001;

    private ListView mDrawerList;
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private ArrayAdapter<String> mDrawerAdapter;

    private FirebaseRecyclerAdapter<Boolean, RoomHolder> mRecyclerViewAdapter;
    private SwipeRefreshLayout swipeContainer;

    public String groupName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rooms);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        createDrawer();

        createRoomsView();

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAddRoomDialog();
            }
        });

        doesDefaultRoomExist();

        displayTitle();
    }

    private void displayTitle() {
        String groupKey = getGroupKey();
        DatabaseReference drGroup = FirebaseDatabase.getInstance().getReference(AppConstant.DB_GROUPS).child(groupKey).child("name");
        drGroup.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    return;
                }
                String name = (String) dataSnapshot.getValue();
                groupName = name;
                displayTitle(name);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "displayTitle() database error=" + databaseError.getMessage());
            }
        });
    }

    public void displayTitle(String name) {
        if (name == null) {
            return;
        }
        String title = name + " " + getString(R.string.title_activity_rooms);
        getSupportActionBar().setTitle(title);
    }

    private String getGroupKey() {
        // This room resides under this groupKey
        return getIntent().getStringExtra(AppConstant.GROUP_KEY);
    }

    private void doesDefaultRoomExist() {
        // root/users/userkey/defaults/groups/groupKey/room/roomKey
        Query defaultRoomQuery = FirebaseDatabase.getInstance().getReference(AppConstant.DB_USERS).child(getUserKey()).child(AppConstant.DB_DEFAULTS).child(AppConstant.DB_GROUPS).child(getGroupKey()).child(AppConstant.DB_ROOM);
        defaultRoomQuery.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    createDefaultRoom();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "::doesDefaultRoomsExist? " + databaseError.getMessage());
                // TODO
                Toast.makeText(RoomsActivity.this, "Oops Couldn't find default group", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void createDefaultRoom() {
        createRoom("My Room", true);
    }

    /**
     * Link to user rooms with reference
     *
     * @param roomKey
     */
    private void linkUserToRoom(final String roomKey, boolean defaultRoom) {
        // root/users/userkey/rooms/roomkey/true
        FirebaseDatabase.getInstance().getReference(AppConstant.DB_USERS).child(getUserKey()).child(AppConstant.DB_ROOMS).child(roomKey).setValue(true);

        // root/users/userkey/groups_rooms/groupKey/rooms/roomkey/true
        FirebaseDatabase.getInstance().getReference(AppConstant.DB_USERS).child(getUserKey()).child(AppConstant.DB_GROUPS_ROOMS).child(getGroupKey()).child(AppConstant.DB_ROOMS).child(roomKey).setValue(true);

        if (defaultRoom) {
            // root/users/userkey/defaults/room/roomKey
            FirebaseDatabase.getInstance().getReference(AppConstant.DB_USERS).child(getUserKey()).child(AppConstant.DB_DEFAULTS).child(AppConstant.DB_GROUPS).child(getGroupKey()).child(AppConstant.DB_ROOM).setValue(roomKey);
        }
    }

    private void createRoomsView() {
        swipeContainer = (SwipeRefreshLayout) findViewById(R.id.swipeContainer);
        swipeContainer.setColorSchemeResources(android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        swipeContainer.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                swipeContainer.setRefreshing(true);
                mRecyclerViewAdapter.notifyDataSetChanged();

                (new Handler()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        swipeContainer.setRefreshing(false);
                    }
                }, 2000);
            }
        });

        Log.i(TAG, "createRoomsView getUserKey()=" + getUserKey() + " getGroupKey()=" + getGroupKey());

        // root/users/userkey/groups_rooms/groupKey/rooms/roomkey
        DatabaseReference drRooms = FirebaseDatabase.getInstance().getReference(AppConstant.DB_USERS).child(getUserKey()).child(AppConstant.DB_GROUPS_ROOMS).child(getGroupKey()).child(AppConstant.DB_ROOMS);
        drRooms.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                (new Handler()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        swipeContainer.setRefreshing(false);
                    }
                }, 500);
            }

            @Override
            public void onCancelled(DatabaseError firebaseError) {
                swipeContainer.setRefreshing(false);
            }
        });

        mRecyclerViewAdapter = new FirebaseRecyclerAdapter<Boolean, RoomHolder>(Boolean.class, R.layout.room, RoomHolder.class, drRooms) {
            @Override
            public void populateViewHolder(final RoomHolder roomHolder, Boolean b, final int position) {
                final String roomKey = mRecyclerViewAdapter.getRef(position).getRef().getKey();

                roomHolder.setRoom(getSupportFragmentManager(), roomKey);
                roomHolder.setOnClickRoomHandler(new RoomHolder.RoomClickHandler() {
                    @Override
                    public void onRoomClick() {
                        navigateToChat(roomKey);
                    }
                });
            }
        };

        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.roomsList);
        recyclerView.setHasFixedSize(false);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(mRecyclerViewAdapter);
        recyclerView.refreshDrawableState();

        ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int swipeDir) {
                // Remove swiped item from list and notify the RecyclerView
                RoomHolder roomHolder = (RoomHolder) viewHolder;

                showAreYouSureDialog(roomHolder);
            }
        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerView);
    }

    private void navigateToChat(String roomKey) {
        startActivity(ChatsActivity.createIntent(this, getGroupKey(), roomKey));
    }

    private void createDrawer() {
        mDrawerList = (ListView) findViewById(R.id.navList);
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        addDrawerItems();

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.drawer_open, R.string.drawer_close) {
            /** Called when a drawer has settled in a completely open state. */
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                invalidateOptionsMenu(); // creates call to onPrepareOptionsMenu()
            }

            /** Called when a drawer has settled in a completely closed state. */
            public void onDrawerClosed(View view) {
                super.onDrawerClosed(view);
                invalidateOptionsMenu(); // creates call to onPrepareOptionsMenu()
            }
        };

        mDrawerToggle.setDrawerIndicatorEnabled(true);
        mDrawerLayout.setDrawerListener(mDrawerToggle);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);
    }

    private void addDrawerItems() {
        String[] osArray = {"Android", "iOS", "Windows", "OS X", "Linux"};
        mDrawerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, osArray);
        mDrawerList.setAdapter(mDrawerAdapter);

        mDrawerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Toast.makeText(RoomsActivity.this, "Time for an upgrade!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showAreYouSureDialog(RoomHolder roomHolder) {
        DeleteRoomDialog deleteRoomDialog = DeleteRoomDialog.newInstance(roomHolder);
        deleteRoomDialog.show(getSupportFragmentManager(), "Delete Room Dialog Fragment");
    }

    private void showAddRoomDialog() {
        EditRoomDialog dialogFragment = EditRoomDialog.newInstance();
        dialogFragment.show(getSupportFragmentManager(), "Add Room Dialog Fragment");
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        mDrawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_rooms, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                onActionSettings();
                break;
            case R.id.action_signout:
                onActionSignout();
                break;
            case R.id.action_groups:
                onActionGroups();
                break;
            case R.id.action_share_group:
                onActionShareGroup();
                break;
        }

        // Activate the navigation drawer toggle
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void onActionShareGroup() {
        String url = getString(R.string.invitation_deep_link_group) + getGroupKey();
        String title = getString(R.string.invite_group) + " " + groupName;

        String message = getString(R.string.invite_group_message, groupName);

        Intent intent = new AppInviteInvitation.IntentBuilder(title)
                .setMessage(message)
                .setDeepLink(Uri.parse(url))
                .setCallToActionText(getString(R.string.invite_group_button_text))
                .build();

        startActivityForResult(intent, REQUEST_INVITE);
    }

    private void onActionSettings() {
        Toast.makeText(RoomsActivity.this, "Settings Click", Toast.LENGTH_SHORT).show();
    }

    private void onActionGroups() {
        startActivity(GroupsActivity.createIntent(this));
    }

    private void onActionSignout() {
        FirebaseAuth.getInstance().signOut();
        startActivity(MainActivity.createIntent(RoomsActivity.this));
    }

    /**
     * Create a concrete room, then link it to the user.
     *
     * @param name room name
     */
    private void createRoom(String name, final boolean defaultRoom) {
        Room room = new Room(name);
        room.setUid(FirebaseAuth.getInstance().getCurrentUser().getUid());

        // add concrete room, then link it to user
        DatabaseReference dr = FirebaseDatabase.getInstance().getReference(AppConstant.DB_ROOMS);
        dr.push().setValue(room, new DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                String roomKey = databaseReference.getKey();
                if (databaseError == null) {
                    linkUserToRoom(roomKey, defaultRoom);
                } else {
                    // TODO
                    Log.e(TAG, "Error completing creating room.");
                }
            }
        });
    }

    /**
     * Finished adding.
     */
    @Override
    public void onFinishAddDialog(String roomName) {
        //Log.i(TAG, "roomName=" + roomName + " " + addRoom);
        createRoom(roomName, false);
    }

    /**
     * Finished editing.
     */
    @Override
    public void onFinishEditDialog(final String roomName, int adapterIndex) {
        DatabaseReference drLink = mRecyclerViewAdapter.getRef(adapterIndex).getRef();
        drLink.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                String roomKey = dataSnapshot.getKey();
                // root/rooms/roomKey/name
                FirebaseDatabase.getInstance().getReference(AppConstant.DB_ROOMS).child(roomKey).child("name").setValue(roomName);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                // TODO
            }
        });
    }

    @Override
    public void onDeleteOkDialog(RoomHolder roomHolder) {
        Log.i(TAG, "delete Ok roomName=" + roomHolder.getRoom().getName());
        Toast.makeText(this, "Deleting room " + roomHolder.getRoom().getName(), Toast.LENGTH_LONG).show();

        // TODO delete room
    }

    @Override
    public void onDeleteCancelDialog(RoomHolder roomHolder) {
        Log.i(TAG, "delete Cancel roomName=" + roomHolder.getRoom().getName());
        Toast.makeText(this, "Delete canceled " + roomHolder.getRoom().getName(), Toast.LENGTH_LONG).show();

        mRecyclerViewAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mRecyclerViewAdapter.cleanup();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        if (requestCode == REQUEST_INVITE) {
            if (resultCode == RESULT_OK) {

                // Get the invitation IDs of all sent messages
                String[] inviteIds = AppInviteInvitation.getInvitationIds(resultCode, data);
                for (String inviteId : inviteIds) {
                    Log.d(TAG, "onActivityResult: sent invitation inviteId=" + inviteId + " groupKey=" + getGroupKey());

                    // root/invites/inviteid/group/groupkey
                    FirebaseDatabase.getInstance().getReference(AppConstant.DB_INVITES).child(inviteId)
                            .child(AppConstant.DB_GROUP).child(getGroupKey()).child("name").setValue(groupName);
                }
            } else {
                Toast.makeText(this, "The invitation was cancelled.", Toast.LENGTH_LONG).show();
            }
        }
    }

}
