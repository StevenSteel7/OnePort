package oneport.test;
import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;

public class EditorTest extends Instrumentation {
 Bundle args; Activity activity; StringBuilder evidence=new StringBuilder();
 public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 interface Condition { boolean ready() throws Exception; }
 void record(String s){android.util.Log.i("OnePortCompatTest",s);evidence.append(s).append('\n');}
 void waitFor(String label,Condition c)throws Exception{long until=SystemClock.uptimeMillis()+120000;while(SystemClock.uptimeMillis()<until){if(c.ready()){record(label+": PASS");return;}Thread.sleep(200);}throw new Exception("timeout: "+label);}
 Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 View find(View v,String text){if(android.text.TextUtils.equals(text,v.getContentDescription()))return v;if(v instanceof TextView && android.text.TextUtils.equals(text,((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View r=find(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
 void click(String text)throws Exception{final View[] found={null};runOnMainSync(()->{View v=find(activity.getWindow().getDecorView(),text);found[0]=v;if(v!=null){while(!v.isClickable() && v.getParent() instanceof View)v=(View)v.getParent();v.performClick();}});if(found[0]==null)throw new Exception("missing view "+text);record("clicked "+text);}
 boolean accessibleClick(String text){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(android.view.accessibility.AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text)){if(android.text.TextUtils.equals(text,n.getText())){while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null)return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);}}return false;}
 void touch(View v,float x0,float x1){runOnMainSync(()->{long t=SystemClock.uptimeMillis();MotionEvent a=MotionEvent.obtain(t,t,0,x0,v.getHeight()/2f,0);v.dispatchTouchEvent(a);a.recycle();a=MotionEvent.obtain(t,t+300,2,x1,v.getHeight()/2f,0);v.dispatchTouchEvent(a);a.recycle();a=MotionEvent.obtain(t,t+400,1,x1,v.getHeight()/2f,0);v.dispatchTouchEvent(a);a.recycle();});}
 void shell(String cmd)throws Exception{android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(cmd);try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[4096];while(in.read(b)!=-1){}}}
 void snapshot(String name)throws Exception{android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new Exception("screenshot unavailable");try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();}}
 public void onStart(){Bundle result=new Bundle();try{
  Intent in=new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.samsung.app.newtrim","com.samsung.app.newtrim.trimactivity.TrimActivity")).setDataAndType(Uri.parse(args.getString("uri")),"video/mp4").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
  ActivityMonitor monitor=addMonitor("com.samsung.app.newtrim.trimactivity.TrimActivity",null,false);
  shell("am start -W -n com.samsung.app.newtrim/.trimactivity.TrimActivity -a android.intent.action.MAIN -d "+args.getString("uri")+" -t video/mp4 -f 0x10008001");
  activity=monitor.waitForActivityWithTimeout(30000); if(activity==null)throw new Exception("activity did not launch"); waitFor("preview prepared",()->Boolean.TRUE.equals(field(activity,"mPrepared")));
  MediaPlayer player=(MediaPlayer)field(activity,"mPlayer");
  runOnMainSync(()->{try{View preview=(View)field(activity,"mPreviewBox");preview.performClick();}catch(Exception e){throw new RuntimeException(e);}});
  waitFor("preview playing",()->player.isPlaying());int p=player.getCurrentPosition();waitFor("preview position advances",()->player.getCurrentPosition()>p+100);record("preview position advanced "+p+" -> "+player.getCurrentPosition());
  runOnMainSync(()->{try{((View)field(activity,"mPreviewBox")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
  if(player.isPlaying())throw new Exception("pause failed");record("preview pause: PASS");
  View trim=(View)field(activity,"mTrim");float density=activity.getResources().getDisplayMetrics().density;float left=14*density,right=trim.getWidth()-left;
  touch(trim,(left+right)*.5f,(left+right)*.5f);Thread.sleep(600);record("scrub position "+player.getCurrentPosition());
  touch(trim,left/2,left/2+(right-left)*.3f);long start=(Long)field(trim,"mStart");if(start<1000)throw new Exception("trim handle did not move: "+start);record("trim start="+start+" end="+field(trim,"mEnd"));
  if(!"replace".equals(args.getString("mode"))){click("Rotate");click("Sound");record("rotation turns="+field(activity,"mRotationTurns")+" muted="+field(activity,"mMuted"));}
  snapshot("oneport-editor.png");click("Save"); boolean replace="replace".equals(args.getString("mode"));
  waitFor("save choice",()->accessibleClick(replace?"Replace original":"Save as copy"));
  if(replace){waitFor("replace confirmation",()->accessibleClick("Replace"));waitFor("write grant or export started",()->field(activity,"mExporter")!=null||accessibleClick("Allow"));}
  waitFor("export finished/activity closed",()->activity.isFinishing()||activity.isDestroyed());
  record("SUCCESS");result.putString("result",evidence.toString());finish(Activity.RESULT_OK,result);
 }catch(Throwable e){record("FAIL "+e);result.putString("result",evidence.toString());result.putString("stack",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
