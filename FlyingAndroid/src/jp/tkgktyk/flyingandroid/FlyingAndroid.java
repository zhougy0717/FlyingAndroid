package jp.tkgktyk.flyingandroid;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class FlyingAndroid implements IXposedHookZygoteInit,
		IXposedHookLoadPackage {
	public static final String PACKAGE_NAME = FlyingAndroid.class.getPackage()
			.getName();
	public static final String ACTION_TOGGLE = PACKAGE_NAME + ".ACTION_TOGGLE";

	private static XSharedPreferences sPref;
	private static Set<String> sIgnoreSet;

	private static String FA_HELPER = "FA_helper";

	/**
	 * I want to FlyingHelper attaches to a DecorView, but some Window has
	 * multiple DecorViews (e.g. when including TagHost). If attach to a
	 * DecorView directly, FlyingHelper is created a number of DecorViews
	 * (TabHosts). This is undesirable behavior. Here attach to a RootView of
	 * DecorView as workaround.
	 * 
	 * @param decor
	 * @param helper
	 */
	private void setFlyingHelper(ViewGroup decor, FlyingHelper helper) {
		// I want to a FlyingHelper attaches to a DecorView, but some Window has
		// multiple DecorViews (e.g. TagHost). To avoid it, attach to RootView
		// of DecorView.
		XposedHelpers.setAdditionalInstanceField(decor.getRootView(),
				FA_HELPER, helper);
		XposedHelpers.setAdditionalInstanceField(decor.getRootView(),
				"FA_attached", true);
	}

	/**
	 * Find a FlyingHelper attached to a DecorView by
	 * {@link FlyingAndroid#setFlyingHelper(ViewGroup, FlyingHelper)} and return
	 * it.
	 * 
	 * @param activity
	 * @return
	 */
	private FlyingHelper getFlyingHelper(Activity activity) {
		return getFlyingHelper((ViewGroup) activity.getWindow().peekDecorView());
	}

	/**
	 * Find a FlyingHelper attached to a DecorView by
	 * {@link FlyingAndroid#setFlyingHelper(ViewGroup, FlyingHelper)} and return
	 * it.
	 * 
	 * @param activity
	 * @return
	 */
	private FlyingHelper getFlyingHelper(ViewGroup decor) {
		return (FlyingHelper) XposedHelpers.getAdditionalInstanceField(
				decor.getRootView(), FA_HELPER);
	}

	@Override
	public void initZygote(StartupParam startupParam) {
		sPref = new XSharedPreferences(PACKAGE_NAME);
		sPref.makeWorldReadable();
		sIgnoreSet = new HashSet<String>();
		sIgnoreSet.add(PACKAGE_NAME);

		try {
			XposedHelpers.findAndHookMethod(ViewGroup.class, "addView",
					View.class, ViewGroup.LayoutParams.class,
					new XC_MethodReplacement() {
						@Override
						protected Object replaceHookedMethod(
								MethodHookParam param) {
							try {
								boolean handled = false;
								View child = (View) param.args[0];
								ViewGroup.LayoutParams layoutParams = (ViewGroup.LayoutParams) param.args[1];
								// class.getName() equals
								// "com.android.internal.policy.impl.PhoneWindow$DecorView"
								// class.getCanonicalName() equals
								// "com.android.internal.policy.impl.PhoneWindow.DecorView"
								// NOTICE: getCanonicalName() returns null in
								// Galaxy's InCall.
								if (param.thisObject
										.getClass()
										.getName()
										.equals("com.android.internal.policy.impl.PhoneWindow$DecorView")) {
									final ViewGroup decor = (ViewGroup) param.thisObject;
									String packageName = decor.getContext()
											.getPackageName();
									if (!sIgnoreSet.contains(packageName)) {
										FlyingAndroidSettings settings = new FlyingAndroidSettings(
												sPref);
										log("reload settings at " + packageName);
										if (!settings.blackSet
												.contains(packageName)) {
											FlyingHelper helper = getFlyingHelper(decor);
											if (helper == null) {
												helper = new FlyingHelper(
														settings);
												// vertical drag interface is
												// enabled on
												// floating window only.
												Context context = decor
														.getContext();
												TypedArray a = context
														.getTheme()
														.obtainStyledAttributes(
																new int[] { android.R.attr.windowIsFloating });
												boolean floating = a
														.getBoolean(0, false);
												a.recycle();
												if (floating) {
													helper.installForFloatingWindow(context);
												} else {
													settings.overwriteUsePinByWhiteList(packageName);
													helper.install(context);
												}
												View newChild = helper
														.getFlyingView();
												// to avoid stack overflow
												// (recursive),
												// call addView(View, int,
												// LayoutParams)
												XposedHelpers.callMethod(
														param.thisObject,
														"addView",
														newChild,
														-1,
														newChild.getLayoutParams());
												//
												setFlyingHelper(decor, helper);
											}
											log("FA_attached = "
													+ (Boolean) XposedHelpers
															.getAdditionalInstanceField(
																	decor,
																	"FA_attached"));
											helper.addViewToFlyingView(child,
													layoutParams);
											handled = true;
										}
									}
								}
								if (!handled) {
									// don't touch original method
									// to avoid stack overflow (recursive),
									// call addView(View, int, LayoutParams)
									XposedHelpers.callMethod(param.thisObject,
											"addView", child, -1, layoutParams);
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
							return null;
						}
					});
			XposedHelpers.findAndHookMethod(Activity.class, "onPostCreate",
					Bundle.class, new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param)
								throws Throwable {
							try {
								final Activity activity = (Activity) param.thisObject;
								final FlyingHelper helper = getFlyingHelper(activity);
								if (helper != null) {
									if (helper.getSettings().forceSetWindowBackground) {
										// force set window background for clear
										// background.
										TypedArray a = activity
												.getTheme()
												.obtainStyledAttributes(
														new int[] { android.R.attr.windowBackground });
										int background = a.getResourceId(0, 0);
										a.recycle();
										if (background != 0) {
											activity.getWindow()
													.setBackgroundDrawableResource(
															background);
										} else {
											log("window background is 0.");
										}
									}
								} else {
									log("FlyingHelper is not found.");
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
			XposedHelpers.findAndHookMethod(Activity.class, "onResume",
					new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param)
								throws Throwable {
							try {
								Activity activity = (Activity) param.thisObject;
								FlyingHelper helper = getFlyingHelper(activity);
								if (helper != null) {
									if (!helper.receiverRegistered()) {
										BroadcastReceiver receiver = helper
												.getToggleReceiver();
										activity.registerReceiver(receiver,
												new IntentFilter(ACTION_TOGGLE));
										helper.onReceiverRegistered();
										log("register");
									}
								} else {
									log("FlyingHelper is not found.");
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
			XposedHelpers.findAndHookMethod(Activity.class, "onPause",
					new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param)
								throws Throwable {
							try {
								Activity activity = (Activity) param.thisObject;
								FlyingHelper helper = getFlyingHelper(activity);
								if (helper != null) {
									if (helper.receiverRegistered()) {
										BroadcastReceiver receiver = helper
												.getToggleReceiver();
										activity.unregisterReceiver(receiver);
										helper.onReceiverUnregistered();
										log("unregister");
									}
								} else {
									log("FlyingHelper is not found.");
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
			XposedHelpers.findAndHookMethod(Activity.class,
					"onConfigurationChanged", Configuration.class,
					new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param)
								throws Throwable {
							try {
								Activity activity = (Activity) param.thisObject;
								FlyingHelper helper = getFlyingHelper(activity);
								if (helper != null) {
									FlyingView flyingView = helper
											.getFlyingView();
									Configuration newConfig = (Configuration) param.args[0];
									if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
										flyingView.rotate();
									} else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
										flyingView.rotate();
									}
								} else {
									log("FlyingHelper is not found.");
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
		} catch (Throwable t) {
			XposedBridge.log(t);
		}
	}

	private void log(String text) {
		if (BuildConfig.DEBUG) {
			XposedBridge.log("FA [DEBUG]: " + text);
		}
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		try {
			if (!lpparam.packageName.equals("com.android.systemui")) {
				return;
			}
			findAndHookMethod(
					"com.android.systemui.statusbar.phone.PhoneStatusBar",
					lpparam.classLoader, "makeStatusBarView",
					new XC_MethodHook() {
						@Override
						protected void afterHookedMethod(MethodHookParam param)
								throws Throwable {
							try {
								FlyingAndroidSettings settings = new FlyingAndroidSettings(
										sPref);
								if (settings.useFlyingStatusBar()) {
									Context context = (Context) XposedHelpers
											.getObjectField(param.thisObject,
													"mContext");
									ViewGroup panel = (ViewGroup) XposedHelpers
											.getObjectField(param.thisObject,
													"mNotificationPanel");
									FlyingHelper helper = new FlyingHelper(
											settings);
									helper.installWithPinShownAlways(context);
									ViewGroup newContents = helper
											.getFlyingView();
									List<View> contents = new ArrayList<View>();
									for (int i = 0; i < panel.getChildCount(); ++i) {
										contents.add(panel.getChildAt(i));
									}
									log("children: " + panel.getChildCount());
									panel.removeAllViews();
									for (View v : contents) {
										helper.addViewToFlyingView(v,
												v.getLayoutParams());
									}
									panel.addView(newContents);
								}
							} catch (Throwable t) {
								XposedBridge.log(t);
							}
						}
					});
		} catch (Throwable t) {
			XposedBridge.log(t);
		}
	}
}
