package dfs;

import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;
import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;

import com.hazelcast.config.Config;
import com.hazelcast.core.*;


/**
 * Example to watch a directory (or tree) for changes to files.
 */

// class WatcherThread implements Runnable{
//     final static ITopic<String> topic;
//     boolean watching;
//     WatcherThread(ITopic<String> t){
//         topic = t;
//         watching = true;
//     }
//     public void run(){
//         while(watching){
//              // wait for key to be signalled
//             WatchKey key;
//             try {
//                 key = watcher.take();
//             } catch (InterruptedException x) {
//                 return;
//             }

//             Path dir = keys.get(key);
//             if (dir == null) {
//                 System.err.println("WatchKey not recognized!!");
//                 continue;
//             }

//             for (WatchEvent<?> event: key.pollEvents()) {
//                 WatchEvent.Kind kind = event.kind();

//                 // TBD - provide example of how OVERFLOW event is handled
//                 if (kind == OVERFLOW) {
//                     System.out.println("Encountered OVERFLOW Event - " + event);
//                     continue;
//                 }

//                 // Context for directory entry event is the file name of entry
//                 WatchEvent<Path> ev = cast(event);
//                 Path name = ev.context();
//                 Path child = dir.resolve(name);

//                 // print out event
//                 System.out.format("%s: %s\n", event.kind().name(), child);

//                 // if directory is created, and watching recursively, then
//                 // register it and its sub-directories
//                 if (recursive && (kind == ENTRY_CREATE)) {
          
//           try {
//                         if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
//                             registerAll(child);
//                         }
//                     } catch (IOException x) {
//                         // ignore to keep sample readbale
//                     }
//                 }
//             }

//             // reset key and remove from set if directory no longer accessible
//             boolean valid = key.reset();
//             if (!valid) {
//                 keys.remove(key);

//                 // all directories are inaccessible
//                 if (keys.isEmpty()) {
//                     break;
//                 }
//             }
//         }



//         }
//     }
// }


public class WatchDir implements Runnable{

    private final WatchService watcher;
    private final Map<WatchKey,Path> keys;
    private final boolean recursive;
    private boolean trace = false;

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
        // try{
        //     this(dir,true,t);
        // }
        // catch(Exception e){
        //     System.out.println("[WatchDir] Some exception occured : e="+e);
        // }
    }

    /**
     * Process all events for keys queued to the watcher
     */

    private static void forwardToItopic(WatchEvent.Kind<Path> kind, Path dir){
        boolean isDir = Files.isDirectory(dir);
        if(kind == ENTRY_CREATE){
            if(!isDir){
                topic.publish(new Action("add_file",dir.toString()));
            }
            else{
                topic.publish(new Action("create_folder",dir.toString()));
            }
        }
        else if(kind == ENTRY_DELETE){
            topic.publish(new Action("delete_entry",dir.toString()));
        }
        else if(kind == ENTRY_MODIFY){
            if(!isDir)
                topic.publish(new Action("edit_file",dir.toString()));
        }
        else{
            //TODO
            System.out.println("[forwardToItopic] Unexpected Event - kind="+kind+"dir="+dir);
        }
    }

    //void processEvents() {
    public void run(){   
        for (;;){
            // wait for key to be signalled
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

                forwardToItopic(kind,child);

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