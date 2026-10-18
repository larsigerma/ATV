//! Independent ADB control plane. JSON is not additionally encrypted here; the existing
//! authenticated peer stream retains its transport policy. Never log request payloads.
use hbb_common::message_proto::{Message, Misc};
use serde_json::{json, Value};

const MAX_REQUEST: usize = 32768;
const MAX_REPLY: usize = 524288; // JSON escaping of a bounded 64 KiB shell result.
const PENDING_TTL_SECS: u64 = 310; // Eight operations of at most 35s, plus reply margin.

pub(crate) fn message(value: String) -> Message {
    let mut misc = Misc::new();
    misc.set_remote_adb(value);
    let mut msg = Message::new();
    msg.set_misc(misc);
    msg
}

fn error(id: &str, code: &str, text: &str) -> String {
    json!({"v":1,"id":id,"ok":false,"error":code,"message":text}).to_string()
}

#[cfg(any(target_os = "android", target_os = "ios", feature = "flutter"))]
mod pc {
    use super::*;
    use crate::{client::{Data, Interface}, flutter::sessions};
    use std::{collections::HashMap, sync::Mutex, time::{Duration, Instant}};
    use uuid::Uuid;

    struct Pending {
        session: Uuid,
        peer: String,
        since: Instant,
        reply: Option<String>,
    }
    lazy_static::lazy_static! {
        static ref PENDING: Mutex<HashMap<String, Pending>> = Mutex::new(HashMap::new());
    }

    pub fn controller(session_id: Uuid, key: &str, param: &str) -> Option<String> {
        let mut pending = PENDING.lock().ok()?;
        pending.retain(|_, p| p.since.elapsed() < Duration::from_secs(PENDING_TTL_SECS));
        if key == "remote_adb_poll" || key == "remote_adb_forget" {
            let p = pending.get(param)?;
            if p.session != session_id { return None; }
            if key == "remote_adb_forget" { pending.remove(param); return Some(String::new()); }
            if p.reply.is_none() { return Some(String::new()); }
            return pending.remove(param)?.reply;
        }
        if param.len() > MAX_REQUEST { return Some(error("", "invalid", "ADB 请求过大。")); }
        if pending.len() >= 64 { return Some(error("", "busy", "ADB 请求过多，请稍后重试。")); }
        let session = sessions::get_session_by_session_id(&session_id)?;
        let mut request: Value = serde_json::from_str(param).ok()?;
        if !request.is_object() { return None; }
        let id = Uuid::new_v4().to_string();
        request["id"] = json!(id);
        request["v"] = json!(1);
        request["kind"] = json!("request");
        let sender = session.sender.read().ok()?.clone()?;
        pending.insert(id.clone(), Pending {
            session: session_id, peer: session.get_id(), since: Instant::now(), reply: None,
        });
        if sender.send(Data::Message(message(request.to_string()))).is_err() {
            pending.remove(&id);
            return Some(error("", "offline", "远程会话已断开。"));
        }
        Some(json!({"id":id}).to_string())
    }

    pub fn receive(peer: &str, payload: &str) {
        if payload.len() > MAX_REPLY { return; }
        let Ok(value) = serde_json::from_str::<Value>(payload) else { return; };
        if value["v"] == 1 && value["kind"] == "state" {
            #[cfg(all(feature = "flutter", not(any(target_os = "android", target_os = "ios"))))]
            crate::remote_adb_video::pc::receive_adb_state(peer, value);
            return;
        }
        if value["v"] != 1 || !value["ok"].is_boolean() { return; }
        let Some(id) = value["id"].as_str() else { return; };
        if let Ok(mut pending) = PENDING.lock() {
            if let Some(p) = pending.get_mut(id) {
                if p.peer == peer && p.reply.is_none() { p.reply = Some(payload.to_owned()); }
            }
        }
    }
}
#[cfg(any(target_os = "android", target_os = "ios", feature = "flutter"))]
pub use pc::{controller, receive};

#[cfg(not(target_os = "ios"))]
pub fn dispatch(owner: i32, normal_session: bool, control: bool, clipboard: bool, payload: &str, tx: crate::server::Sender) {
    if payload.len() > MAX_REQUEST { return; }
    let Ok(value) = serde_json::from_str::<Value>(payload) else { return; };
    let Some(id) = value["id"].as_str().filter(|s| s.len() <= 64) else { return; };
    if value["v"] != 1 || value["kind"] != "request" { return; }
    let status_request = value["method"] == "status";
    let reply = if !normal_session || (!status_request && !control) || (value["method"] == "clipboard" && !clipboard) {
        Some(error(id, "permission", "当前会话没有远程控制权限，不能操作 ADB。"))
    } else {
        #[cfg(target_os = "android")]
        { android::submit(owner, id, payload, control, tx.clone()) }
        #[cfg(not(target_os = "android"))]
        { let _ = owner; Some(error(id, "unsupported", "远端未提供 Android ADB 模块。")) }
    };
    if let Some(reply) = reply {
        let _ = tx.send((hbb_common::tokio::time::Instant::now(), std::sync::Arc::new(message(reply))));
    }
}

#[cfg(target_os = "android")]
pub mod android {
    use super::*;
    use jni::{objects::{GlobalRef, JClass, JObject, JString, JValue}, JNIEnv, JavaVM};
    use std::{collections::HashMap, sync::{Arc, Mutex}, time::{Duration, Instant}};
    use crate::server::Sender;

    struct Bridge { vm: JavaVM, manager: GlobalRef }
    struct Reply { owner: i32, id: String, control: bool, tx: Sender, since: Instant }
    lazy_static::lazy_static! {
        static ref BRIDGE: Mutex<Option<Bridge>> = Mutex::new(None);
        static ref REPLIES: Mutex<HashMap<String, Reply>> = Mutex::new(HashMap::new());
    }

    // Java registers an instance from its own class loader. Rust owns a process-lifetime
    // GlobalRef; each call attaches this thread, and only posts work to Android's main looper.
    #[no_mangle]
    pub extern "system" fn Java_me_ashell_localadb_RemoteAdbNative_register(
        env: JNIEnv, _class: JClass, manager: JObject,
    ) {
        if let (Ok(vm), Ok(manager)) = (env.get_java_vm(), env.new_global_ref(manager)) {
            if let Ok(mut bridge) = BRIDGE.lock() { *bridge = Some(Bridge { vm, manager }); }
        }
    }

    fn invoke(method: &str, signature: &str, args: &[&str]) -> bool {
        let Ok(bridge) = BRIDGE.lock() else { return false; };
        let Some(bridge) = bridge.as_ref() else { return false; };
        let Ok(mut env) = bridge.vm.attach_current_thread() else { return false; };
        let result = env.with_local_frame(16, |env| -> jni::errors::Result<()> {
            let strings = args.iter().map(|s| env.new_string(*s)).collect::<Result<Vec<_>, _>>()?;
            let objects: Vec<JObject> = strings.into_iter().map(Into::into).collect();
            let values: Vec<JValue> = objects.iter().map(JValue::Object).collect();
            env.call_method(bridge.manager.as_obj(), method, signature, &values)?;
            Ok(())
        });
        if result.is_err() { let _ = env.exception_clear(); }
        result.is_ok()
    }

    pub fn submit(owner: i32, id: &str, payload: &str, control: bool, tx: Sender) -> Option<String> {
        let token = uuid::Uuid::new_v4().to_string();
        {
            let Ok(mut replies) = REPLIES.lock() else { return Some(error(id, "busy", "ADB 模块忙。")); };
            replies.retain(|_, r| r.since.elapsed() < Duration::from_secs(PENDING_TTL_SECS));
            if replies.len() >= 64 || replies.values().filter(|r| r.owner == owner).count() >= 10 {
                return Some(error(id, "busy", "ADB 请求过多，请稍后重试。"));
            }
            replies.insert(token.clone(), Reply { owner, id: id.into(), control, tx, since: Instant::now() });
        }
        if !invoke("dispatchRemote", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                   &[&owner.to_string(), &token, payload]) {
            if let Ok(mut replies) = REPLIES.lock() { replies.remove(&token); }
            return Some(error(id, "unavailable", "Android ADB 模块尚未初始化。"));
        }
        None
    }

    #[no_mangle]
    pub extern "system" fn Java_me_ashell_localadb_RemoteAdbNative_state(
        mut env: JNIEnv, _class: JClass, payload: JString,
    ) {
        let Ok(payload) = env.get_string(&payload).map(|s| s.to_string_lossy().into_owned()) else { return; };
        if payload.len() > MAX_REQUEST { return; }
        let Ok(state) = serde_json::from_str::<Value>(&payload) else { return; };
        if !state["supported"].is_boolean() || !state["connected"].is_boolean() { return; }
        crate::remote_adb_video::android::publish_adb_state(state);
    }

    #[no_mangle]
    pub extern "system" fn Java_me_ashell_localadb_RemoteAdbNative_complete(
        mut env: JNIEnv, _class: JClass, token: JString, payload: JString,
    ) {
        let Ok(token) = env.get_string(&token).map(|s| s.to_string_lossy().into_owned()) else { return; };
        let Ok(payload) = env.get_string(&payload).map(|s| s.to_string_lossy().into_owned()) else { return; };
        let reply = REPLIES.lock().ok().and_then(|mut r| r.remove(&token));
        let Some(reply) = reply else { return; };
        let mut value = if payload.len() <= MAX_REPLY { serde_json::from_str::<Value>(&payload).ok() } else { None };
        let payload = if let Some(value) = value.as_mut().filter(|v| v.is_object()) {
            value["v"] = json!(1);
            value["id"] = json!(reply.id);
            value["controlAllowed"] = json!(reply.control);
            value.to_string()
        } else { error(&reply.id, "invalid", "ADB 返回数据无效。") };
        let _ = reply.tx.send((hbb_common::tokio::time::Instant::now(), Arc::new(message(payload))));
    }

    pub fn disconnect(owner: i32) {
        if let Ok(mut replies) = REPLIES.lock() { replies.retain(|_, r| r.owner != owner); }
        cancel_requests(owner);
    }

    pub fn cancel_requests(owner: i32) {
        invoke("disconnectRemote", "(Ljava/lang/String;)V", &[&owner.to_string()]);
    }

    pub fn cancel_clipboard(owner: i32) {
        invoke("cancelClipboardSync", "(Ljava/lang/String;)V", &[&owner.to_string()]);
    }
}
