/**
 * 驱动独立 HTML 交互原型：取景、模拟转写、草稿编辑和本地记录。
 * 不调用 ASR 或业务后端；只有用户主动启用相机时才请求相机权限。
 */
const $ = (id) => document.getElementById(id);
const STORAGE = "murmur-prototype-v1";
let saved;
try {
  saved = JSON.parse(localStorage.getItem(STORAGE) || "{}");
} catch {
  saved = {};
}
let draft = saved.draft || { text: "", images: [] };
let posts = saved.posts || [];
let phase = draft.images.length ? "photo" : "capture";
let stream,
  tick,
  started,
  baseline = "",
  finalText = "",
  partialText = "";
let visibleSpeech = "";
const textMeasure = document.createElement("canvas").getContext("2d");
let toastTimer,
  pressTimer,
  longPressed = false;
let capturing = false;
const demoSegments = [
  "今天想记住一个很小的想法。",
  "做自己擅长的事情，",
  "获得正反馈的时间会更短。",
  "也许可以先从一个小作品开始。",
];
for (let i = 0; i < 40; i++) $("wave").append(document.createElement("i"));
/** 将正文、附件和本地记录保存到浏览器；存储不足时明确提示，不清空当前草稿。 */
function persist() {
  try {
    localStorage.setItem(STORAGE, JSON.stringify({ draft, posts }));
    $("save-state").textContent = "草稿已保存";
    return true;
  } catch {
    $("save-state").textContent = "保存失败";
    toast("浏览器存储不足，请移除部分图片");
    return false;
  }
}
/** 显示短暂操作反馈，不改变页面状态。 */
function toast(text) {
  $("toast").textContent = text;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("toast").hidden = true), 2500);
}
/** 根据当前状态更新取景、照片与主按钮，仅影响界面。 */
function render() {
  $("shutter-controls").hidden = phase !== "capture";
  $("photo-controls").hidden = phase !== "photo";
  $("record-controls").hidden = phase !== "recording";
  $("recording-panel").hidden = phase !== "recording";
  $("viewfinder").classList.toggle("recording", phase === "recording");
  $("capture-photo").hidden = !draft.images.length;
  if (draft.images.length) $("capture-photo").src = draft.images[0];
  $("photo-count").hidden = !draft.images.length || phase === "recording";
  $("photo-count").textContent = `${draft.images.length} 张照片`;
  $("enable-camera").hidden = phase !== "capture" || !!stream;
  $("draft-dot").hidden = !draft.text && !draft.images.length;
}
/** 拍摄当前画面；没有相机时生成独立的演示插画快照，不伪装成真实照片。 */
async function capture() {
  if (phase !== "capture" || capturing) return;
  capturing = true;
  try {
    let photo;
    if (stream) {
      if (!$("camera").videoWidth) {
        toast("相机准备中，请稍后");
        return;
      }
      const canvas = document.createElement("canvas");
      canvas.width = 900;
      canvas.height = 900;
      const video = $("camera");
      const size = Math.min(video.videoWidth, video.videoHeight);
      canvas
        .getContext("2d")
        .drawImage(
          video,
          (video.videoWidth - size) / 2,
          (video.videoHeight - size) / 2,
          size,
          size,
          0,
          0,
          900,
          900,
        );
      photo = canvas.toDataURL("image/jpeg", 0.8);
    } else {
      const svg = await (await fetch("scene.svg")).text();
      photo = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
    }
    draft.images.push(photo);
    persist();
    phase = "photo";
    render();
    $("flash").classList.remove("flash");
    void $("flash").offsetWidth;
    $("flash").classList.add("flash");
  } catch {
    toast("拍摄失败，草稿仍然保留");
  } finally {
    capturing = false;
  }
}
/** 仅在主动点击后启用设备相机，权限拒绝时继续保留演示画面。 */
async function enableCamera() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "environment" },
      audio: false,
    });
    $("camera").srcObject = stream;
    $("camera").hidden = false;
    render();
  } catch {
    toast("无法启用相机，仍可使用演示取景");
  }
}
/** 按可用像素宽度保留末尾文字，避免换行或裁掉最后一个字；不改变完整草稿。 */
function renderTranscriptTail() {
  const box = $("live-text").parentElement;
  textMeasure.font = getComputedStyle(box).font;
  const budget = Math.max(0, box.clientWidth - 16);
  const chars = Array.from(visibleSpeech);
  while (chars.length && textMeasure.measureText(chars.join("")).width > budget)
    chars.shift();
  $("live-text").textContent = chars.join("");
}
window.addEventListener("resize", renderTranscriptTail);
/** 开始模拟流式识别；完整文本进入草稿，屏幕只展示尾部文字。 */
function startRecording() {
  if (phase === "recording") return;
  baseline = draft.text;
  finalText = "";
  partialText = "";
  started = Date.now();
  phase = "recording";
  visibleSpeech = "";
  $("live-text").textContent = "";
  $("live-text").parentElement.classList.add("silent");
  $("wave").classList.add("silent");
  $("timer").textContent = "00:00";
  render();
  let segment = 0,
    position = 0,
    silenceUntil = started + 500;
  tick = setInterval(() => {
    const now = Date.now(),
      seconds = Math.floor((now - started) / 1000);
    $("timer").textContent =
      String(Math.floor(seconds / 60)).padStart(2, "0") +
      ":" +
      String(seconds % 60).padStart(2, "0");
    const speaking = now >= silenceUntil && segment < demoSegments.length;
    if (speaking) {
      const text = demoSegments[segment];
      position++;
      // partial 是整段候选的替换值，只有定稿后才追加，避免累计同一片段。
      partialText = text.slice(0, position);
      if (position >= text.length) {
        finalText += text;
        partialText = "";
        position = 0;
        segment++;
        silenceUntil = now + 2200;
      }
      draft.text =
        baseline +
        (baseline && (finalText || partialText) ? "\n" : "") +
        finalText +
        partialText;
      persist();
      visibleSpeech = finalText + partialText;
      renderTranscriptTail();
    }
    const silent = !speaking;
    $("live-text").parentElement.classList.toggle("silent", silent);
    $("wave").classList.toggle("silent", silent);
    if (silent) {
      visibleSpeech = "";
      $("live-text").textContent = "";
    }
    [...$("wave").children].forEach(
      (bar, i) =>
        (bar.style.height = `${speaking ? 5 + Math.abs(Math.sin(now / 190 + i * 0.65)) * 29 * (1 - Math.abs(i - 20) / 26) : 3}px`),
    );
  }, 180);
}
/** 结束模拟录音并保留当前部分文本，进入与铅笔入口共用的编辑弹层。 */
function stopRecording() {
  clearInterval(tick);
  phase = draft.images.length ? "photo" : "capture";
  persist();
  render();
  openEditor();
}
/** 展示可恢复的完整正文和附件，并让真实设备键盘接管输入。 */
function openEditor() {
  if (phase === "recording") {
    stopRecording();
    return;
  }
  $("body").value = draft.text;
  renderAttachments();
  updateEditor();
  $("scrim").hidden = false;
  $("editor").hidden = false;
  document.querySelector("header").inert = true;
  $("capture-view").inert = true;
  $("history-view").inert = true;
  $("body").focus();
}
/** 关闭编辑弹层并保留草稿，不创建记录。 */
function closeEditor() {
  document.querySelector("header").inert = false;
  $("capture-view").inert = false;
  $("history-view").inert = false;
  persist();
  $("editor").hidden = true;
  $("scrim").hidden = true;
  $("body").blur();
  render();
}
/** 更新字数和发送可用性，不改变正文。 */
function updateEditor() {
  $("send").disabled = !draft.text.trim() && !draft.images.length;
}
/** 展示附件缩略图，移除操作同步保存草稿。 */
function renderAttachments() {
  $("attachments").replaceChildren();
  draft.images.forEach((src, index) => {
    const item = document.createElement("div");
    item.className = "attachment";
    const image = new Image();
    image.src = src;
    image.alt = `附件 ${index + 1}`;
    const button = document.createElement("button");
    button.textContent = "×";
    button.setAttribute("aria-label", `移除图片 ${index + 1}`);
    button.onclick = () => {
      draft.images.splice(index, 1);
      persist();
      renderAttachments();
      updateEditor();
      render();
    };
    item.append(image, button);
    $("attachments").append(item);
  });
}
/** 将选择的图片缩小为预览附件，限制本地存储占用；不上传文件。 */
async function addImages(event) {
  for (const file of event.target.files) {
    if (draft.images.length >= 6) {
      toast("原型最多保存 6 张附件");
      break;
    }
    try {
      const bitmap = await createImageBitmap(file);
      const canvas = document.createElement("canvas");
      const scale = Math.min(1, 1000 / Math.max(bitmap.width, bitmap.height));
      canvas.width = bitmap.width * scale;
      canvas.height = bitmap.height * scale;
      canvas
        .getContext("2d")
        .drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close();
      draft.images.push(canvas.toDataURL("image/jpeg", 0.75));
    } catch {
      toast("这张图片暂时无法读取");
    }
  }
  event.target.value = "";
  persist();
  renderAttachments();
  updateEditor();
  render();
}
/** 保存为浏览器内的原型记录；失败时回退内存状态并保留草稿。 */
function send() {
  if (!draft.text.trim() && !draft.images.length) return;
  const previous = draft;
  const post = {
    text: draft.text,
    images: [...draft.images],
    at: new Date().toISOString(),
  };
  posts.unshift(post);
  draft = { text: "", images: [] };
  if (!persist()) {
    posts.shift();
    draft = previous;
    return;
  }
  closeEditor();
  phase = "capture";
  render();
  showHistory();
  toast("已保存到此浏览器");
}
/** 渲染本地记录；正文使用 textContent，避免用户输入被当作 HTML 执行。 */
function showHistory() {
  if (phase === "recording") {
    toast("请先结束录音");
    return;
  }
  $("capture-view").hidden = true;
  $("history-view").hidden = false;
  $("post-list").replaceChildren();
  if (!posts.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "还没有记录，先留下一个瞬间吧。";
    $("post-list").append(empty);
  }
  posts.forEach((post) => {
    const article = document.createElement("article");
    article.className = "post";
    const time = document.createElement("time");
    time.textContent = new Date(post.at).toLocaleString("zh-CN", {
      month: "long",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
    const body = document.createElement("p");
    body.textContent = post.text;
    article.append(time, body);
    post.images.forEach((src) => {
      const image = new Image();
      image.src = src;
      image.alt = "记录图片";
      article.append(image);
    });
    $("post-list").append(article);
  });
}
$("shutter").addEventListener("pointerdown", (e) => {
  if (e.button !== 0) return;
  longPressed = false;
  $("shutter").setPointerCapture(e.pointerId);
  pressTimer = setTimeout(() => {
    longPressed = true;
    startRecording();
  }, 450);
});
$("shutter").addEventListener("pointerup", () => {
  clearTimeout(pressTimer);
  if (!longPressed) void capture();
});
$("shutter").addEventListener("pointercancel", () => clearTimeout(pressTimer));
$("shutter").addEventListener("contextmenu", (e) => e.preventDefault());
$("shutter").addEventListener("click", (e) => {
  if (e.detail === 0) void capture();
});
$("shutter").addEventListener("keydown", (e) => {
  if (e.key.toLowerCase() === "r") {
    e.preventDefault();
    startRecording();
  }
});
$("record").onclick = startRecording;
$("stop").onclick = stopRecording;
$("edit").onclick = openEditor;
$("resume").onclick = openEditor;
$("close-editor").onclick = closeEditor;
$("scrim").onclick = closeEditor;
$("send").onclick = send;
$("body").oninput = () => {
  draft.text = $("body").value;
  persist();
  updateEditor();
};
$("enable-camera").onclick = enableCamera;
$("add-image").onclick = () => $("image-input").click();
$("image-input").onchange = addImages;
$("history").onclick = showHistory;
$("back-capture").onclick = () => {
  $("history-view").hidden = true;
  $("capture-view").hidden = false;
  render();
};
$("retake").onclick = () => {
  draft.images = [];
  persist();
  phase = "capture";
  render();
};
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("editor").hidden) closeEditor();
});
window.addEventListener("pagehide", () => {
  clearInterval(tick);
  persist();
  stream?.getTracks().forEach((track) => track.stop());
});
render();
