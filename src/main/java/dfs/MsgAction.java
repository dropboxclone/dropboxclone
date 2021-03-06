package dfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import com.hazelcast.core.HazelcastInstance;
import java.nio.file.FileSystems;

import com.hazelcast.core.*;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.nio.file.StandardCopyOption;

public class MsgAction implements MessageListener<Action>{
	static HazelcastInstance instance;
	public MsgAction(HazelcastInstance i){
		instance = i;
	}

	public static void deleteFileorFolderOnDisk(java.io.File f){
		if(!f.isDirectory())
			f.delete();
		else{
			for(java.io.File sub : f.listFiles())
				deleteFileorFolderOnDisk(sub);
			f.delete();
		}
	}

	public static Map<String,Timer> timers = new HashMap<String,Timer>();
	public static ConcurrentHashMap<String,Boolean> hasTimerStarted = new  ConcurrentHashMap<String,Boolean>();

	// public void onMessage(Message<Action> msg){
	// 	Action act = msg.getMessageObject();
	// 	act.setPath(Folder.getFileSystemPath(act.getPath()).toString());
	// 	if(act.getAction().equals("add_file") || act.getAction().equals("edit_file")){
	// 		//int lastBackSlashIndex = act.getPath().lastIndexOf("/");
	// 		//String parentFolderPath = act.getPath().substring(0,lastBackSlashIndex);
	// 		//String fileName = act.getPath().substring(lastBackSlashIndex+1);
	// 		String parentFolderPath = Folder.locateParentFolder(Paths.get(act.getPath()));
	// 		String fileName = (new java.io.File(act.getPath())).getName(); 

	// 		Map<String,FileOrFolder> contents = instance.getMap(parentFolderPath);
	// 		File newFile = (File) contents.get(fileName);
	// 		Path newFilePath = Paths.get(act.getPath());
	// 		try{
	// 			if(Files.exists(newFilePath)){
	// 				if(!Arrays.equals(Files.readAllBytes(newFilePath),newFile.getContents())){
	// 					try{
	// 						Files.write(newFilePath,newFile.getContents());
	// 					}
	// 					catch(Exception e){
	// 						System.out.println("[MsgAction] Exception in inner e="+e);
	// 					}
	// 				}
	// 			}
	// 			else{
	// 				Files.write(newFilePath,newFile.getContents());
	// 			}
	// 		}
	// 		catch(Exception e){
	// 			System.out.println("[MsgAction] Exception in outer e="+e);
	// 		}
	// 	}
	// 	else if(act.getAction().equals("delete_file")){
	// 		//todo delete from internal representation
	// 		int lastBackSlashIndex = act.getPath().lastIndexOf("/");
	// 		String parentFolderPath = act.getPath().substring(0,lastBackSlashIndex);
	// 		String fileName = act.getPath().substring(lastBackSlashIndex+1);
	// 		try{
	// 			Files.delete(FileSystems.getDefault().getPath(act.getPath()));
	// 		}
	// 		catch(Exception e){
	// 			System.out.println("[MsgAction] Exception e="+e);
	// 		}
	// 	}
	// 	else if(act.getAction().equals("create_folder")){
	// 		//TODO : will create conflict incase directory already present
	// 		try{
	// 			Files.createDirectory(FileSystems.getDefault().getPath(act.getPath()));
	// 		}
	// 		catch(Exception e){
	// 			System.out.println("[MsgAction] Exception e="+e);
	// 		}
	// 	}
	// 	else if(act.getAction().equals("delete_folder")){
	// 		//java.io.File top = new java.io.File(act.getPath());
	// 		//for(java.io.File sub : top.listFiles()){
	// 		deleteFileorFolderOnDisk(new java.io.File(act.getPath()));
	// 	}
	// 	else if(act.getAction().equals("delete_entry")){
	// 		deleteFileorFolderOnDisk(new java.io.File(act.getPath()));
	// 	}
	// 	else{
	// 		//TODO
	// 		System.out.println("[MsgAction] unexpected action="+act);
	// 	}
	// }

	public static String memberInfoToString(Member m){
		return "Member{"+m+",isLocalMember="+m.localMember()+",attributes="+m.getAttributes()+"}";
	};

	public void onMessage(Message<Action> msg){
		long inTime = System.currentTimeMillis();
		Action act = msg.getMessageObject();
		System.out.println("[MsgAction]#"+msg.hashCode()+" INFO : Received Action="+act + ", from " + memberInfoToString(msg.getPublishingMember()) + " @"+Main.timeToString(inTime));
		if(msg.getPublishingMember().localMember()){
			System.out.println("Self msg : ignored"); //DEBUG
			return;
		}
		String internalPath = act.getPath();
		Folder.dontWatch.add(internalPath);
		Timer prevTimer = timers.get(internalPath);
		if(prevTimer != null){
			prevTimer.cancel();
			timers.remove(internalPath);
		}
		if(hasTimerStarted.get(internalPath)==Boolean.TRUE){
			while(hasTimerStarted.get(internalPath)==Boolean.TRUE);
		}
		System.out.println("MsgAction#"+msg.hashCode()+" INFO: Added "+internalPath+" to don't watch list."); //DEBUG
		if(act.getAction().equals("add_file") || act.getAction().equals("edit_file")) {
			Folder.loadFileFromInternalToFS(internalPath);
		} else if (act.getAction().equals("delete_file")) {
			Folder.deleteFileFromFS(internalPath);
		} else if(act.getAction().equals("create_folder") || act.getAction().equals("edit_folder")) {
			Folder.loadFolderFromInternalToFS(internalPath);
		} else if(act.getAction().equals("delete_file")) {
			Folder.deleteFolderFromFS(internalPath);
		} else if(act.getAction().equals("delete_entry")) {
			Folder.deleteFromFS(internalPath);
		} else if(act.getAction().equals("create_empty_folder")) {
			Folder.createEmptyFolderInFS(internalPath);
		} else{
			//TODO
	 		System.err.println("[MsgAction] unexpected action="+act);
		}
		Timer timerToRemoveIntPath = new Timer();
		timers.put(internalPath,timerToRemoveIntPath);
		hasTimerStarted.put(internalPath,false);
		timerToRemoveIntPath.schedule(new TimerTask(){
			@Override
			public void run(){
				hasTimerStarted.put(internalPath,true);
				Folder.dontWatch.remove(internalPath);
				long outTime = System.currentTimeMillis();
				System.out.println("MsgAction#"+msg.hashCode()+" INFO: Removed "+internalPath+" from don't watch list @"+Main.timeToString(outTime)); //DEBUG
				hasTimerStarted.remove(internalPath,true);
			}
		},200);
	}
}