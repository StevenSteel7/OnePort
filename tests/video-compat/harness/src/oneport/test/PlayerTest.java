package oneport.test;
import android.app.*;import android.os.*;import android.content.*;import android.media.MediaPlayer;import android.view.*;import java.lang.reflect.*;
public class PlayerTest extends Instrumentation{
 Bundle args; Activity a;StringBuilder out=new StringBuilder();
 public void onCreate(Bundle b){super.onCreate(b);args=b;start();}void log(String s){android.util.Log.i("OnePortPlayerTest",s);out.append(s).append('\n');}
 Object field(Object o,String n)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new Exception("missing field "+n);}
 Object invoke(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
 interface Check{boolean ok()throws Exception;}void waitFor(String label,Check c)throws Exception{long until=SystemClock.uptimeMillis()+60000;while(SystemClock.uptimeMillis()<until){if(c.ok()){log(label+": PASS");return;}Thread.sleep(200);}throw new Exception("timeout "+label);}
 Object[] control={null};MediaPlayer[] player={null};
 void shell(String cmd)throws Exception{android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(cmd);try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[4096];while(in.read(b)!=-1){}}}
 void snapshot(String name)throws Exception{android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new Exception("screenshot unavailable");try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();}}
 public void onStart(){Bundle r=new Bundle();try{
 shell("settings put secure immersive_mode_confirmations confirmed");
 ActivityMonitor mon=addMonitor("com.samsung.android.video.player.activity.MoviePlayer",null,false);
 shell("am start -W -n com.samsung.android.video/com.samsung.android.video.player.activity.MoviePlayer -a android.intent.action.VIEW -d "+args.getString("uri")+" -t video/mp4 -f 0x10008001");
 a=mon.waitForActivityWithTimeout(30000);if(a==null)throw new Exception("no activity");
 final Object[] utilHolder={null}; final Throwable[] initFailure={null};runOnMainSync(()->{try{Class<?> single=getTargetContext().getClassLoader().loadClass("jb.v1");utilHolder[0]=single.getField("a").get(null);}catch(Throwable e){initFailure[0]=e;}});if(initFailure[0]!=null)throw new Exception("player singleton initialization",initFailure[0]);Object util=utilHolder[0];
 waitFor("media control bound",()->{control[0]=field(util,"f");return control[0]!=null;});log("control class "+control[0].getClass().getName());
 waitFor("media player present",()->{try{Object actual=control[0].getClass().getName().equals("va.v")?field(control[0],"i"):control[0]; Object sem=field(actual,"M");if(sem==null)return false;player[0]=(MediaPlayer)field(sem,"player");return true;}catch(Exception e){return false;}});
 waitFor("playback running",()->player[0].isPlaying());int p=player[0].getCurrentPosition();waitFor("playback advances",()->player[0].getCurrentPosition()>p+100);log("position "+p+" -> "+player[0].getCurrentPosition()+" duration="+player[0].getDuration());
 // Exercise the live player instance used by the app; no separate test decoder.
 runOnMainSync(()->player[0].pause());if(player[0].isPlaying())throw new Exception("pause failed");log("pause live player: PASS");
 runOnMainSync(()->player[0].seekTo(2000,MediaPlayer.SEEK_CLOSEST));waitFor("seek live player to2s",()->Math.abs(player[0].getCurrentPosition()-2000)<500);
 runOnMainSync(()->player[0].start());waitFor("resume live player",()->player[0].isPlaying());
 final int fullWidth=a.getWindow().getDecorView().getWidth(); final boolean[] entered={false};runOnMainSync(()->entered[0]=a.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(16,9)).build()));
 waitFor("system PiP",()->entered[0]&&a.isInPictureInPictureMode());waitFor("PiP window resized",()->a.getWindow().getDecorView().getWidth()<fullWidth*.8f);Thread.sleep(2500);if(!a.isInPictureInPictureMode()||!player[0].isPlaying())throw new Exception("PiP playback stopped");log("PiP playback and window "+a.getWindow().getDecorView().getWidth()+"x"+a.getWindow().getDecorView().getHeight());snapshot("oneport-player-pip.png");log("SUCCESS");r.putString("result",out.toString());finish(Activity.RESULT_OK,r);
 }catch(Throwable e){log("FAIL "+e);r.putString("result",out.toString());r.putString("stack",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,r);}}
}
