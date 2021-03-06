package dfs;

import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;
import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;


import com.hazelcast.config.Config;
import com.hazelcast.core.*;

public class WatchDir implements Runnable{

    private final WatchService watcher;
    private final Map<WatchKey,Path> keys;
    private final boolean recursive;
    private boolean trace = false;
    // private static long minDelayBtwnWatchEvents = 10;

    private static ITopic<Action> topic;

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>)event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException
            {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Creates a WatchService and registers the given directory
     */
    WatchDir(Path dir, boolean recursive, ITopic<Action> t) throws IOException {
        topic = t;
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey,Path>();
        this.recursive = recursive;

        if (recursive) {
            System.out.format("Scanning %s ...\n", dir);
            registerAll(dir);
            System.out.println("Done.");
        } else {
            register(dir);
        }

        // enable trace after initial registration
        this.trace = true;

    }

    WatchDir(Path dir, ITopic<Action> t) throws IOException{
        this(dir,true,t);
    }

    /**
     * Process all events for keys queued to the watcher
     */

    private static void forwardToItopic(WatchEvent.Kind<Path> kind, Path dir){
        boolean isDir = Files.isDirectory(dir);
        if(kind == ENTRY_CREATE){
            if(!isDir){
                Action act = new Action("add_file",Folder.getInternalPath(dir));
                //Folder.getFileFromDiskToWinSafe(act.getPath());
                Folder.loadFileFromFSToInternal(dir);
                topic.publish(act);
            }
            else{
                if(Folder.isEmptyFSFolder(dir)) {
                    Folder.createEmptyFolderInInternal(dir);
                    topic.publish(new Action("create_empty_folder",Folder.getInternalPath(dir)));
                } else {
                    Folder.loadFolderFromFSToInternal(dir);
                    topic.publish(new Action("create_folder",Folder.getInternalPath(dir)));
                }
            }
        }
        else if(kind == ENTRY_DELETE){
            //todo
            Folder.deleteFromInternal(dir);
            topic.publish(new Action("delete_entry",Folder.getInternalPath(dir)));
        }
        else if(kind == ENTRY_MODIFY){
            //todo
            if(!isDir){
                Folder.loadFileFromFSToInternal(dir);
                topic.publish(new Action("edit_file",Folder.getInternalPath(dir)));
            }
            else{
                if(Folder.isEmptyFSFolder(dir)) {
                    Folder.createEmptyFolderInInternal(dir);
                    topic.publish(new Action("create_empty_folder",Folder.getInternalPath(dir)));
                } else {
                    Folder.loadFolderFromFSToInternal(dir);
                    topic.publish(new Action("edit_folder",Folder.getInternalPath(dir)));
                }
            }
        }
        else{
            //TODO
            System.out.println("[forwardToItopic] Unexpected Event - kind="+kind+"dir="+dir);
        }
    }

    //void processEvents() {
    public void run(){
        System.out.println("WatchDir Thread INFO: priority="+Thread.currentThread().getPriority());  
        for (;;){
            // wait for key to be signalled
            System.out.println("WatchDir INFO: restarting loop...acquiring new key");
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }

            for (WatchEvent<?> event: key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();
                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    System.out.println("Encountered OVERFLOW Event - " + event);
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                // print out event
                System.out.format("[WatchDir] %s: %s\n", event.kind().name(), child);

                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if (recursive && (kind == ENTRY_CREATE)) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    } catch (IOException x) {
                        // ignore to keep sample readbale
                    }
                }

                long t = System.currentTimeMillis();
                if(!Folder.dontWatch.contains(Folder.getInternalPath(child))){
                    Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                    System.out.println("WatchDir#"+key+" INFO: path="+child+ ", internal=" + Folder.getInternalPath(child) + " is NOT in don't watch list. Forwarding it to other peers. @"+Main.timeToString(t)); //DEBUG
                    forwardToItopic(kind,child);
                } else {
                    Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                    System.out.println("WatchDir#"+key+" INFO: path="+child+ ", internal=" + Folder.getInternalPath(child) + " IS in the don't watch list. NOT forwarding. @"+Main.timeToString(t)); //DEBUG
                    // try{
                    //     Thread.sleep(minDelayBtwnWatchEvents);
                    // } catch(InterruptedException ex) {
                    //     System.err.println("Exception:"+ex+" while trying to sleep WatchDir thread");
                    //     ex.printStackTrace();
                    // }
                }

            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }
}