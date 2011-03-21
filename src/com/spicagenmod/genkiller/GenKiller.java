package com.spicagenmod.genkiller;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import android.app.ActivityManager;
import android.app.ListActivity;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;

public final class GenKiller extends ListActivity implements OnClickListener {
	private static final String PREFS_FILENAME = "GenKiller.conf";
	private static final String ShowLockedKey = "ShowLocked";
	private static final String PREFS_WHITE_LIST = "WhiteList";
	private static final int MENU_KILL = 0;
	private SharedPreferences prefs;
	private List<String> runningPackages = new ArrayList<String>();
	private List<String> reservedPackages = new ArrayList<String>();
	private List<String> whiteListPackages = new ArrayList<String>();
	private ActivityManager activityManager;
	private PackageAdapter adapter;
	private boolean showLocked;
	private Method killMethod;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		this.setContentView(R.layout.main);
		this.activityManager = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);
		this.prefs = this.getSharedPreferences(PREFS_FILENAME, MODE_PRIVATE);

		if (this.reservedPackages.isEmpty()) {
			this.reservedPackages.add("system");
			this.reservedPackages.add("com.google.process.gapps");
			this.reservedPackages.add("android.process.acore");
			this.reservedPackages.add("android.process.media");
		}

		this.loadWhiteList();
		this.initializeButtons();
		this.initializeKillMethod();

		this.adapter = new PackageAdapter(
			this.getApplicationContext(),
			this.runningPackages,
			this.whiteListPackages,
			this.showLocked);
		this.setListAdapter(this.adapter);
		ListView listView = this.getListView();
		listView.setTextFilterEnabled(false);
		this.registerForContextMenu(listView);
	}

	private void initializeButtons() {
		Button killButton = (Button) this.findViewById(R.id.btnKill);
		killButton.setOnClickListener(this);

		this.showLocked = this.prefs.getBoolean(ShowLockedKey, true);
		final ToggleButton showHideButton = (ToggleButton) this.findViewById(R.id.btnShowHide);
		showHideButton.setChecked(showLocked);
		showHideButton.setOnClickListener(this);
	}

	private void initializeKillMethod() {
		try {
			this.killMethod = ActivityManager.class.getMethod("killBackgroundProcesses", String.class);
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}

		if (this.killMethod != null) {
			return;
		}

		try {
			this.killMethod = ActivityManager.class.getMethod("restartPackage", String.class);
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
		ImageView image = (ImageView) info.targetView.findViewById(R.id.icon);
		TextView text = (TextView) info.targetView.findViewById(R.id.text);
		menu.setHeaderIcon(image.getDrawable());
		menu.setHeaderTitle(text.getText());
		menu.add(0, MENU_KILL, 0, R.string.txt_menu_kill);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
		String packageName = (String) this.adapter.getItem(info.position);

		switch (item.getItemId()) {
		case MENU_KILL:
			this.kill(packageName);
			this.refreshTaskList();
			break;
		}

		return super.onContextItemSelected(item);
	}

	private void startPackageAction(String packageName, String action) {
		Intent intent = new Intent(action);
		Uri data = Uri.fromParts("package", packageName, null);
		intent.setData(data);
		this.startActivity(intent);
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		String packageName = (String)this.adapter.getItem(position);

		if (this.whiteListPackages.contains(packageName)) {
			this.whiteListPackages.remove(packageName);
		} else {
			this.whiteListPackages.add(packageName);
		}

		this.adapter.notifyDataSetChanged();
	}

    @Override
    protected void onPause() {
        this.saveWhiteList();
        super.onPause();
    }

	@Override
	protected void onDestroy() {
		this.saveWhiteList();
		super.onDestroy();
	}

	@Override
	protected void onRestart() {
		super.onRestart();
		refreshTaskList();
	}

	@Override
	protected void onStart() {
		super.onStart();
		refreshTaskList();
	}

	private void loadWhiteList() {
		this.whiteListPackages.clear();
		
		for (int i=0; i <64; i++) {
			String packageName = this.prefs.getString(PREFS_WHITE_LIST + i, null);
			
			if (packageName == null) {
				break;
			}
			
			this.whiteListPackages.add(packageName);
		}
	}

	private void saveWhiteList() {
		SharedPreferences.Editor editor = this.prefs.edit();

		for (int i=0; i <64; i++) {
			String packageName = (this.whiteListPackages.size() > i) ? this.whiteListPackages.get(i) : null;
			editor.putString(PREFS_WHITE_LIST + i, packageName);
		}

		editor.commit();
	}

	private void refreshTaskList() {
		List<RunningAppProcessInfo> processList = this.activityManager.getRunningAppProcesses();
		this.runningPackages.clear();

		for (RunningAppProcessInfo procInfo : processList) {
			String packageName = procInfo.processName.split(":")[0];
			
			if (this.getPackageName().equals(packageName) || (this.reservedPackages.contains(packageName))) {
				continue;
			}

			if (!this.runningPackages.contains(packageName)) {
				this.runningPackages.add(packageName);
			}
		}

		this.adapter.refresh();
	}

	private static class PackageAdapter extends BaseAdapter {
		private LayoutInflater inflater;
		private List<String> packageNames;
		private List<String> lockedNames;
		private List<String> visibleNames;
		private PackageManager packageManager;
		private Bitmap iconNotFound;
		private Bitmap iconLocker;
        private Bitmap iconEmpty;
		private boolean showLocked;

		public PackageAdapter(Context context, List<String> packageNames, List<String> reservedNames, boolean showLocked) {
			this.inflater = LayoutInflater.from(context);
			this.packageNames = packageNames;
			this.lockedNames = reservedNames;
			this.packageManager = context.getPackageManager();
			this.iconNotFound = BitmapFactory.decodeResource(context.getResources(), android.R.drawable.sym_def_app_icon);
			this.iconLocker = BitmapFactory.decodeResource(context.getResources(), R.drawable.locker);
			this.iconEmpty = BitmapFactory.decodeResource(context.getResources(), R.drawable.empty);
			this.showLocked = showLocked;
			this.calcVisibleNames();
		}

		public int getCount() {
			return this.visibleNames.size();
		}

		public Object getItem(int position) {
			return this.visibleNames.get(position);
		}

		public long getItemId(int position) {
			return position;
		}

		public void showLocked(boolean show) {
			if (this.showLocked != show) {
				this.showLocked = show;
				this.refresh();
			}
		}

		public void refresh() {
			this.calcVisibleNames();
			this.notifyDataSetChanged();
		}
		
		public View getView(int position, View convertView, ViewGroup parent) {
			ViewHolder holder;

			if (convertView == null) {
				convertView = this.inflater.inflate(R.layout.list_item_icon_text, null);

				holder = new ViewHolder();
				holder.text = (TextView) convertView.findViewById(R.id.text);
				holder.icon = (ImageView) convertView.findViewById(R.id.icon);
				holder.fav = (ImageView) convertView.findViewById(R.id.fav);

				convertView.setTag(holder);
			} else {
				holder = (ViewHolder) convertView.getTag();
			}

			String packageName = this.visibleNames.get(position);

			try {
				ApplicationInfo appInfo = this.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
				String label = packageManager.getApplicationLabel(appInfo).toString();
				Drawable drawable = packageManager.getApplicationIcon(appInfo);

				holder.text.setText(label);
				holder.icon.setImageDrawable(drawable);

			} catch (NameNotFoundException e) {
				holder.text.setText(this.visibleNames.get(position));
				holder.icon.setImageBitmap(this.iconNotFound);
			}

			boolean isLocked = lockedNames.contains(packageName);
            holder.fav.setImageBitmap(isLocked ? this.iconLocker : this.iconEmpty);
			return convertView;
		}

		private static class ViewHolder {
			TextView text;
			ImageView icon;
			ImageView fav;
		}

		private void calcVisibleNames() {
			if (this.showLocked) {
				this.visibleNames = this.packageNames;
			} else {
				this.visibleNames = new ArrayList<String>();

				for (String name : this.packageNames) {
					if (!this.lockedNames.contains(name)) {
						this.visibleNames.add(name);
					}
				}
			}
		}
	}

	private void kill(String packageName) {
		if (this.killMethod != null) {
			try {
				this.killMethod.invoke(this.activityManager, packageName);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onClick(View v) {
		switch (v.getId()) {
		case R.id.btnKill:
			for (String packageName : runningPackages) {
				if (!whiteListPackages.contains(packageName)) {
					kill(packageName);
				}
			}
			saveWhiteList();
			android.os.Process.killProcess(android.os.Process.myPid());
			System.exit(0);
			break;
		case R.id.btnShowHide:
			showLocked = ((ToggleButton) this.findViewById(R.id.btnShowHide))
					.isChecked();
			SharedPreferences.Editor editor = prefs.edit();
			editor.putBoolean(ShowLockedKey, showLocked);
			editor.commit();
			adapter.showLocked(showLocked);
			break;
		}
	}
}
