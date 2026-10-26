package app.zmby.data

/**
 * The 用手机输入 web page (ui-spec §2.13, TokenPhone.dc.html): one column on a phone, centred up to
 * 560 on larger screens; no external assets. The value is masked until 「显示」 and is sent once,
 * in the body of `POST /input/<code>`, when 「保存到电视」 is pressed.
 */
internal object PhoneInputPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    fun html(field: InputField): String {
        val link = field.helpUrl?.let { """<a class="btn link" href="${esc(it)}" target="_blank" rel="noopener noreferrer">打开 ${esc(field.source)} 的 API 设置页 ↗</a>""" }.orEmpty()
        val rows = if (field.singleLine) 2 else 5
        val note = if (field == InputField.UserAgent) "只用于这台电视连接你的服务器" else "${field.label}只保存在电视上"
        return """
<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>zmby 用手机输入</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0a0b0d;color:#fff;font:16px/1.5 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:16px;display:flex;flex-direction:column;gap:18px}
header{display:flex;align-items:center;justify-content:space-between;height:44px}.brand{font-size:19px;font-weight:800;letter-spacing:-.5px}
.tv{display:inline-flex;align-items:center;gap:8px;height:32px;padding:0 12px;border-radius:999px;font-size:12px;font-weight:600;background:linear-gradient(#ffffff38,#ffffff14);box-shadow:inset 0 1px #ffffff66}
.dot{width:7px;height:7px;border-radius:99px;background:#71717a}.dot.ok{background:#4ade80}
.src{font-size:13px;font-weight:700;color:#ffffff99;letter-spacing:1px}h1{margin:0;font-size:26px;line-height:1.2;font-weight:800;letter-spacing:-.5px}
.card{border-radius:22px;background:#1a1b1f;box-shadow:inset 0 1px #ffffff1a;padding:16px;display:flex;flex-direction:column;gap:12px}
.btn{display:inline-flex;align-items:center;justify-content:center;min-height:44px;padding:0 18px;border:0;border-radius:999px;background:linear-gradient(#ffffff38,#ffffff14);box-shadow:inset 0 1px #ffffff66;color:#fff;font:inherit;font-size:14px;font-weight:600;text-decoration:none;cursor:pointer}
.link{align-self:flex-start}label span{font-size:13px;font-weight:600;color:#ffffffad}
textarea{width:100%;margin-top:6px;border:0;border-radius:13px;padding:12px 14px;background:#0005;color:#fff;box-shadow:inset 0 1px 3px #0008,inset 0 0 0 1px #ffffff16;font:16px/1.5 ui-monospace,Menlo,monospace;word-break:break-all;resize:vertical}
textarea.masked{-webkit-text-security:disc}textarea:focus{outline:2px solid #fff;outline-offset:1px}
.tools{display:flex;gap:8px}.result{display:none;align-items:center;gap:10px;padding:12px 14px;border-radius:14px;font-size:14px;font-weight:600}
.result.ok{display:flex;background:#4ade801a;color:#4ade80}.result.no{display:flex;background:#fbbf241a;color:#fbbf24}
.primary{width:100%;min-height:52px;border:0;border-radius:999px;background:linear-gradient(#fff,#e4e5e9);color:#0a0b0d;font:inherit;font-size:16px;font-weight:700;cursor:pointer}
.primary:disabled{opacity:.4;cursor:default}.foot{text-align:center;font-size:13px;color:#ffffff99}
</style></head><body><main>
<header><span class="brand">zmby</span><span class="tv"><span class="dot" id="dot"></span><span id="tv">正在连接电视…</span></span></header>
<div><div class="src">${esc(field.source)}</div><h1>${esc(field.title)}</h1></div>
<section class="card">$link
<label><span>${esc(field.label)}</span><textarea id="v" class="masked" rows="$rows" spellcheck="false" autocomplete="off" autocapitalize="none" aria-label="${esc(field.label)}"></textarea></label>
<div class="tools"><button class="btn" id="paste" type="button" hidden>粘贴</button><button class="btn" id="show" type="button" aria-pressed="false">显示</button><button class="btn" id="clear" type="button">清空</button></div>
<div class="result" id="result" role="status" aria-live="polite"></div></section>
<button class="primary" id="save" type="button" disabled>保存到电视</button><div class="foot">${esc(note)}</div>
<script>
const base=location.pathname.replace(/\/$/,''),v=document.getElementById('v'),save=document.getElementById('save'),result=document.getElementById('result');
let done=false;
function say(ok,text){result.className='result '+(ok?'ok':'no');result.textContent=text;}
function sync(){save.disabled=done||!v.value.trim();}
v.addEventListener('input',()=>{result.className='result';sync();});
document.getElementById('show').onclick=e=>{const on=v.classList.toggle('masked');e.target.textContent=on?'显示':'隐藏';e.target.setAttribute('aria-pressed',String(!on));};
document.getElementById('clear').onclick=()=>{v.value='';result.className='result';sync();v.focus();};
const paste=document.getElementById('paste');
if(navigator.clipboard&&navigator.clipboard.readText){paste.hidden=false;paste.onclick=async()=>{try{v.value=(await navigator.clipboard.readText()).trim();sync();}catch(e){say(false,'浏览器不允许读取剪贴板，请长按输入框粘贴');}};}
save.onclick=async()=>{save.disabled=true;say(true,'正在验证…');
 try{const r=await fetch(base,{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:v.value});
  let j;try{j=await r.json();}catch(e){j={ok:false,message:await r.text()};}
  say(!!j.ok,j.message||'保存失败');if(j.ok){done=true;v.value='';v.disabled=true;}}
 catch(e){say(false,'连不上电视，请确认手机和电视在同一网络');}
 sync();};
async function ping(){try{const r=await fetch(base+'/ping');if(!r.ok){document.getElementById('tv').textContent='已失效，请在电视上重新打开';document.getElementById('dot').className='dot';save.disabled=true;done=true;return;}
 const j=await r.json();const m=Math.floor(j.remaining/60),s=String(j.remaining%60).padStart(2,'0');
 document.getElementById('tv').textContent=j.device+' · '+m+':'+s;document.getElementById('dot').className='dot ok';}catch(e){}}
ping();setInterval(ping,5000);sync();
</script></main></body></html>
""".trimIndent()
    }
}
