package com.livraison.wifiwatchdog.home;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.livraison.wifiwatchdog.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 入っているアプリの一覧。
 * EXTRA_PICK_SLOT が無ければ、押したアプリを開く(自作ホームには必須)。
 * あれば、押したアプリをそのタイルに割り当てて閉じる。
 */
public class AppListActivity extends BaseActivity {

    static final String EXTRA_PICK_SLOT = "pick_slot";
    /** true ならグループ(動画・会議など)に追加。false なら置き換え */
    static final String EXTRA_ADD = "pick_add";

    private final AppAdapter adapter = new AppAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        Slot slot = Slot.fromKey(getIntent().getStringExtra(EXTRA_PICK_SLOT));
        boolean add = getIntent().getBooleanExtra(EXTRA_ADD, false);
        if (slot == null) setupAppBar("アプリ一覧");
        else setupAppBar(add ? slot.title + "に追加するアプリを選ぶ" : slot.title + "に使うアプリを選ぶ");

        TextView status = findViewById(R.id.app_list_status);
        GridView grid = findViewById(R.id.app_grid);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppCatalog.AppEntry e = adapter.getItem(position);
            if (slot == null) {
                AppCatalog.launch(this, e);
                return;
            }
            List<String> list = add ? HomePrefs.apps(this, slot) : new ArrayList<>();
            if (!list.contains(e.pkg)) list.add(e.pkg);
            HomePrefs.setApps(this, slot, list);
            Toast.makeText(this, "「" + e.label + "」を" + slot.title + "に設定しました",
                    Toast.LENGTH_SHORT).show();
            finish();
        });

        new Thread(() -> {
            List<AppCatalog.AppEntry> apps = AppCatalog.launchableApps(this);
            runOnUiThread(() -> {
                adapter.setItems(apps);
                status.setVisibility(View.GONE);
            });
        }).start();
    }

    private final class AppAdapter extends BaseAdapter {
        private List<AppCatalog.AppEntry> items = new ArrayList<>();

        void setItems(List<AppCatalog.AppEntry> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppCatalog.AppEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_app, parent, false);
            AppCatalog.AppEntry e = getItem(position);
            ((ImageView) v.findViewById(R.id.app_icon)).setImageDrawable(e.icon);
            ((TextView) v.findViewById(R.id.app_label)).setText(e.label);
            return v;
        }
    }
}
