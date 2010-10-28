package org.ruboto;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jruby.Ruby;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.RubyInstanceConfig;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.JavaUtil;
import org.jruby.parser.EvalStaticScope;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.scope.ManyVarsDynamicScope;
import org.jruby.util.KCode;

import android.os.Environment;
import android.util.Log;
import android.content.res.AssetManager;
import android.os.Handler;

import android.util.Log;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;

public class Script {
    public static final String UNTITLED_RB = "untitled.rb";
    public static final boolean MORE_STACK = true;

    private static String scriptsDir = null;
    private static File scriptsDirFile = null;
    private static long scriptsDirModified = 0;
  
    private static final int STATE_EMPTY = 1;
    private static final int STATE_ON_DISK = 2;
    private static final int STATE_IN_MEMORY = 3;
    private static final int STATE_IN_MEMORY_DIRTY = 4;

    private String name = null;
    private static Ruby ruby;
    private static DynamicScope scope;
    private static boolean initialized = false;

    private String contents = null;
    private Integer state = null;

    public static final String TAG = "RUBOTO"; //for logging
    private final static Handler handler = new Handler();

    /*************************************************************************************************
     *
     * Static Methods: JRuby Execution
     */

    public static final FilenameFilter RUBY_FILES = new FilenameFilter() {
        public boolean accept(File dir, String fname) {
            return fname.endsWith(".rb");
        }
    };

    public static boolean initialized() {
        return initialized;
    }

    public static synchronized Ruby setUpJRuby(PrintStream out) {
        if (ruby == null) {
            RubyInstanceConfig config = new RubyInstanceConfig();
            config.setCompileMode(RubyInstanceConfig.CompileMode.OFF);
            config.setKCode(KCode.UTF8);

            config.setLoader(Script.class.getClassLoader());
            if (scriptsDir != null) config.setCurrentDirectory(scriptsDir);

            if (out != null) {
            	config.setOutput(out);
            	config.setError(out);
            }
            
            /* Set up Ruby environment */
            ruby = Ruby.newInstance(config);

            ThreadContext context = ruby.getCurrentContext();
            DynamicScope currentScope = context.getCurrentScope();
            scope = new ManyVarsDynamicScope(new EvalStaticScope(currentScope.getStaticScope()), currentScope);
            
            initialized = true;
        }

        return ruby;
    }

    public static String execute(final String code) {
        if (!initialized) return null;
        boolean moreStack = getGlobalVariable("$stack").isTrue();
        Log.d("Script", "moreStack=" + moreStack);
        if (moreStack) {
          final String[] ret = new String[1];

          try {
              Thread t = new Thread(null, null, "executor", 16*1024) {
                @Override public void run() {
                  try {
                    ret[0] = ruby.evalScriptlet(code, scope).inspect().toJava(String.class).toString();
                  } catch (final RaiseException e) {
                      handler.post(new Runnable() {
                        public void run() {
                           e.printStackTrace(ruby.getErrorStream());
                        }
                      });
                  }
                }
              };
              t.start();
              t.join();

              return ret[0];
          } catch (InterruptedException e) {
              return null;
          }
        } else {
         try {
            return ruby.evalScriptlet(code, scope).inspect().toJava(String.class).toString();
          } catch (RaiseException re) {
            re.printStackTrace(ruby.getErrorStream());
            return null;
          }
       }
    }

    public static void defineGlobalConstant(String name, Object object) {
        ruby.defineGlobalConstant(name, JavaUtil.convertJavaToRuby(ruby, object));
    }
    
    public static void defineGlobalVariable(String name, Object object) {
        ruby.getGlobalVariables().set(name, JavaUtil.convertJavaToRuby(ruby, object));
    }

    public static IRubyObject getGlobalVariable(String name) {
        return ruby.getGlobalVariables().get(name);
    }

    
    public static Ruby getRuby() {
    	return ruby;
    }

    /*************************************************************************************************
    *
    * Static Methods: Scripts Directory
    */
    
    public static void setDir(String dir) {
    	scriptsDir = dir;
    	scriptsDirFile = new File(dir);
        if (ruby != null) ruby.setCurrentDirectory(scriptsDir);
    }
    
    public static String getDir() {
    	return scriptsDir;
    }

    public static File getDirFile() {
    	return scriptsDirFile;
    }

    public static Boolean configDir(String noSdcard) {
      return configDir(null, noSdcard);
    }

    public static Boolean configDir(String sdcard, String noSdcard) {
        if (sdcard != null && Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
        	setDir(sdcard);
        } else {
        	setDir(noSdcard);
        }

        /* Create directory if it doesn't exist */
        if (!scriptsDirFile.exists()) {
            // TODO check return code
            scriptsDirFile.mkdir();
            return true;
        }

        return false;
    }
    
    private static void copyScripts(String from, File to, AssetManager assets) {
        try {
            byte[] buffer = new byte[8192];
            for (String f : assets.list(from)) {
                File dest = new File(to, f);

                if (dest.exists())
                    continue;

                Log.d(TAG, "copying file " + f);

                InputStream is = assets.open(from+ "/" +f);
                OutputStream fos = new BufferedOutputStream(new FileOutputStream(dest));

                int n;
                while ((n = is.read(buffer, 0, buffer.length)) != -1)
                    fos.write(buffer, 0, n);

                is.close();
                fos.close();
            }
        } catch (IOException iox) {
            Log.e(TAG, "error copying demo scripts", iox);
        }
    }

    public static void copyScriptsIfNeeded(String to, AssetManager assets) {
        /* the if makes sure we only do this the first time */
//        if (configDir(to))
//            copyScripts("scripts", scriptsDirFile, assets);
    }

    /*************************************************************************************************
     *
     * Static Methods: Scripts List
     */
    
    public static boolean scriptsDirChanged() {
    	return scriptsDirModified != scriptsDirFile.lastModified();
    }

    public static List<String> list() throws SecurityException {
        return Script.list(new ArrayList<String>());
    }

    public static List<String> list(List<String> list) throws SecurityException {
    	scriptsDirModified = scriptsDirFile.lastModified();
        list.clear();
        String[] tmpList = scriptsDirFile.list(RUBY_FILES);
        Arrays.sort(tmpList, 0, tmpList.length, String.CASE_INSENSITIVE_ORDER);
        list.addAll(Arrays.asList(tmpList));
        return list;
    }

    /*************************************************************************************************
    *
    * Constructors
    */

    public Script(String name) {
        this(name, null);
    }

    public Script(String name, String contents) {
        this.name = name;
        this.contents = contents;
        File file = getFile();

        if (contents == null && !file.exists()) {
            state = STATE_EMPTY;
            this.contents = "";
        } else if (contents == null && file.exists()) {
            state = STATE_ON_DISK;
        } else if (contents != null) {
            state = STATE_IN_MEMORY_DIRTY;
        } else {
            // TODO: Exception
        }
    }

    /* Create a Script from a URL */
    public static Script fromURL(String url) {
    	try {
            String [] temp = url.split("/");
        	DefaultHttpClient client = new DefaultHttpClient();
        	HttpGet get = new HttpGet(url);
        	BasicResponseHandler handler = new BasicResponseHandler();
        	return new Script(temp[temp.length -1], client.execute(get, handler));
    	}
    	catch (Throwable t) {
    		return null;
    	}
    }

    /*************************************************************************************************
     *
     * Attribute Access
     */

    public String getName() {
        return name;
    }

    public File getFile() {
        return new File(getDir(), name);
    }

    public Script setName(String name) {
        this.name = name;
        state = STATE_IN_MEMORY_DIRTY;
        // TODO: Other states possible
        return this;
    }

    public String getContents() throws IOException {
        if (state == STATE_ON_DISK) {
            BufferedReader buffer = new BufferedReader(new FileReader(getFile()));
            StringBuilder source = new StringBuilder();
            while (true) {
                String line = buffer.readLine();
                if (line == null) break;
                source.append(line).append("\n");
            }
            buffer.close();
            contents = source.toString();
            state = STATE_IN_MEMORY;
        }
        return contents;
    }

    public Script setContents(String contents) {
        if (contents == null || contents.equals("")) {
            this.contents = "";
        } else {
            this.contents = contents;
        }
        state = STATE_IN_MEMORY_DIRTY;
        return this;
    }

    /*************************************************************************************************
     *
     * Script Actions
     */

    public void save() throws IOException {
        if (state != STATE_ON_DISK) {
            BufferedWriter buffer = new BufferedWriter(new FileWriter(getFile()));
            buffer.write(contents);
            buffer.close();
            state = STATE_IN_MEMORY;
        }
    }

    public String execute() throws IOException {
        return Script.execute(getContents());
    }

    public boolean delete() {
        return getFile().delete();
    }
}
