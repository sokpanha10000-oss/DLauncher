package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.DisplayInstance;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.Instances;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The page behind the square home button: create a new instance, pick one of the ready
 * instances (the pencil edits it) or exit the launcher.
 */
public class GameMenuFragment extends Fragment {
    public static final String TAG = "GameMenuFragment";

    private final InstanceRowAdapter mAdapter = new InstanceRowAdapter();

    public GameMenuFragment() {
        super(R.layout.fragment_game_menu);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Create new instance: the same page that was reachable from the old version list
        view.findViewById(R.id.menu_create_button).setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), ProfileTypeSelectFragment.class,
                        ProfileTypeSelectFragment.TAG, null));

        view.findViewById(R.id.menu_exit_button).setOnClickListener(v ->
                requireActivity().finishAffinity());

        ListView list = view.findViewById(R.id.menu_instance_list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, itemView, position, id) -> {
            DisplayInstance instance = mAdapter.getItem(position);
            if(instance == null) return;
            Instances.setSelectedInstance(instance);
            Tools.removeCurrentFragment(requireActivity());
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    /** Read the instances again, so that edits made in the editor show up when coming back */
    private void reload() {
        final Context context = requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                final Instances instances = Instances.loadDisplay();
                Tools.runOnUiThread(() -> {
                    if(isAdded()) mAdapter.apply(instances);
                });
            }catch (IOException e) {
                Tools.runOnUiThread(() -> Tools.showError(context, e));
            }
        });
    }

    /** The pencil: select the instance, then open the editor (name, logo, graphics, ...) */
    private void editInstance(DisplayInstance instance) {
        Instances.setSelectedInstance(instance);
        Tools.swapFragment(requireActivity(), InstanceEditorFragment.class,
                InstanceEditorFragment.TAG, null);
    }

    private class InstanceRowAdapter extends BaseAdapter {
        private List<DisplayInstance> mItems = Collections.emptyList();
        private int mSelectedIndex = -1;

        void apply(Instances instances) {
            mItems = instances.list;
            mSelectedIndex = instances.selectedIndex;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public DisplayInstance getItem(int position) {
            if(position < 0 || position >= mItems.size()) return null;
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if(row == null) {
                row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_instance_row, parent, false);
            }
            final DisplayInstance instance = getItem(position);
            if(instance == null) return row;

            ImageView icon = row.findViewById(R.id.instance_row_icon);
            TextView name = row.findViewById(R.id.instance_row_name);
            TextView version = row.findViewById(R.id.instance_row_version);
            ImageButton edit = row.findViewById(R.id.instance_row_edit);

            icon.setImageDrawable(InstanceIconProvider.fetchIcon(row.getResources(), instance));

            String profileName = Tools.validOrNullString(instance.name);
            String versionName = Tools.validOrNullString(instance.versionId);
            if(Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(versionName)) {
                versionName = row.getContext().getString(R.string.profiles_latest_release);
            }else if(Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(versionName)) {
                versionName = row.getContext().getString(R.string.profiles_latest_snapshot);
            }
            name.setText(profileName != null ? profileName : (versionName != null ? versionName : ""));
            version.setText(versionName != null ? versionName : "");

            row.setBackgroundResource(position == mSelectedIndex
                    ? R.drawable.ui_row_selected : R.drawable.ui_row_normal);
            edit.setOnClickListener(v -> editInstance(instance));
            return row;
        }
    }
}
