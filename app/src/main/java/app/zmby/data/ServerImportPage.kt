package app.zmby.data

/** Phone UI served by the temporary import session; all scripts and styles work without internet. */
internal object ServerImportPage {
    val html = """
        <!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>zmby 添加服务器</title><style>
        :root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0a0b0d;color:#eee;font:16px/1.55 system-ui,sans-serif}
        main{max-width:760px;margin:auto;padding:28px 20px 48px}h1{font-size:28px;margin:0 0 8px;letter-spacing:-.6px}h2{font-size:18px;margin:0}
        p{color:#a7adb8;margin:8px 0 18px}section,fieldset{min-width:0;margin:20px 0;padding:20px;border:1px solid #30353e;border-radius:20px;background:#13161b}
        fieldset:disabled{opacity:.6}label{display:block;margin:12px 0 5px;color:#c9ced7;font-size:14px}input,textarea{width:100%;border:1px solid #3b414d;border-radius:10px;background:#0b0d11;color:#eee;padding:12px;font:16px/1.4 system-ui,sans-serif}
        input:focus,textarea:focus{outline:2px solid #92bfff;outline-offset:1px}textarea{height:144px;resize:vertical}button{cursor:pointer;border:0;border-radius:12px;padding:12px 18px;font:600 15px system-ui;background:#e8edf5;color:#12151b}
        button:disabled{cursor:wait;opacity:.6}.secondary{background:#272e39;color:#e0e7f1}.small{padding:8px 12px;font-size:13px}.row{display:flex;gap:10px;align-items:center;justify-content:space-between}.actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:16px}
        .line{border-top:1px solid #30353e;margin-top:16px;padding-top:4px}.line-head{display:flex;gap:10px;align-items:end}.line-head label{flex:1;margin-bottom:0}.line-head button{margin-bottom:2px}
        .hint{font-size:13px;color:#929dab}.status{min-height:24px;margin:12px 0;white-space:pre-wrap}.ok{color:#6de3a4}.no{color:#f4bd70}a{color:#a5c9ff}ul{list-style:none;padding:0}li{padding:8px 0;overflow-wrap:anywhere}
        #submit{width:100%;margin-top:20px;padding:16px}#results{scroll-margin-top:20px}legend{padding:0 8px;color:#aab7ca}input[type=checkbox]{width:auto;margin-right:6px}
        </style></head><body><main>
        <h1>添加服务器</h1><p>把服务器分享信息粘贴到下面，自动填写账号、密码和线路，也可以直接手动填写。</p>
        <section aria-label="粘贴识别"><h2>粘贴识别</h2>
        <label for="paste">服务器分享信息</label><textarea id="paste" spellcheck="false" placeholder="家里 NAS&#10;直连：https://emby.example.com&#10;备用：https://backup.example.com:8920&#10;账号：myname&#10;密码：mypassword"></textarea>
        <div class="actions"><button type="button" class="secondary" id="recognize">重新识别</button><button type="button" class="secondary" id="clear">清空粘贴内容</button></div>
        <div id="notice" class="status hint" role="status" aria-live="polite">粘贴后自动识别，检查表单后再添加。</div></section>
        <form id="form"><div id="servers"></div><div class="actions"><button type="button" class="secondary" id="add-server">＋ 添加另一台服务器</button></div>
        <button id="submit">登录并添加到电视</button></form>
        <ul id="results" aria-live="polite"></ul><p class="hint">密码仅用于登录，不会保存在电视上。离开电视扫码页后，此链接失效。</p><a id="export">导出当前服务器列表</a>
        <template id="server-template"><fieldset class="server"><legend>服务器</legend><div class="row"><h2>连接信息</h2><button type="button" class="secondary small remove-server">移除服务器</button></div>
        <label>服务器名称（可选）<input class="name" autocomplete="off" placeholder="例如：家里 NAS"></label>
        <label>账号<input class="user" autocomplete="username" autocapitalize="none" spellcheck="false" required></label>
        <label>密码<input class="pass" type="password" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="无密码可留空"></label>
        <label><input class="show-pass" type="checkbox">显示密码</label><div class="lines"></div><div class="actions"><button type="button" class="secondary add-line">＋ 添加线路</button></div></fieldset></template>
        <template id="line-template"><div class="line"><div class="line-head"><label>线路名称（可选）<input class="line-name" autocomplete="off" placeholder="例如：直连、备用"></label><button type="button" class="secondary small remove-line">移除线路</button></div>
        <label>线路地址<input class="url" inputmode="url" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="https://emby.example.com:8920" required></label></div></template>
        <script>
        const base = location.pathname.replace(/\/${'$'}/,''), servers = document.getElementById('servers'), form = document.getElementById('form');
        const paste = document.getElementById('paste'), notice = document.getElementById('notice');
        document.getElementById('export').href = base + '/export';
        let busy = false, revision = 0, edited = false;
        function dirty() { edited = true; revision++; }
        function addLine(card, data = {}) {
          const line = document.getElementById('line-template').content.firstElementChild.cloneNode(true);
          line.querySelector('.url').value = data.url || ''; line.querySelector('.line-name').value = data.name || '';
          line.querySelector('.remove-line').onclick = () => { if (card.querySelectorAll('.line').length > 1) { line.remove(); dirty(); } else { line.querySelector('.url').value = ''; line.querySelector('.line-name').value = ''; dirty(); } };
          card.querySelector('.lines').appendChild(line);
        }
        function addServer(data = {}) {
          const card = document.getElementById('server-template').content.firstElementChild.cloneNode(true);
          card.querySelector('.name').value = data.name || ''; card.querySelector('.user').value = data.user || ''; card.querySelector('.pass').value = data.pass || '';
          card.querySelector('.show-pass').onchange = e => { card.querySelector('.pass').type = e.target.checked ? 'text' : 'password'; };
          card.querySelector('.add-line').onclick = () => { addLine(card); dirty(); };
          card.querySelector('.remove-server').onclick = () => { card.remove(); if (!servers.children.length) addServer(); dirty(); };
          for (const line of data.lines && data.lines.length ? data.lines : [{}]) addLine(card, line);
          servers.appendChild(card); return card;
        }
        function readServers() {
          return Array.from(servers.children).map(card => ({name:card.querySelector('.name').value.trim(), user:card.querySelector('.user').value.trim(), pass:card.querySelector('.pass').value,
            lines:Array.from(card.querySelectorAll('.line')).map(line => ({name:line.querySelector('.line-name').value.trim(), url:line.querySelector('.url').value.trim()}))}));
        }
        form.addEventListener('input', dirty);
        paste.addEventListener('input', () => { revision++; });
        document.getElementById('add-server').onclick = () => { addServer(); dirty(); };
        document.getElementById('clear').onclick = () => { paste.value = ''; revision++; notice.textContent = '已清空粘贴内容，表单保留。'; };
        async function recognize() {
          const text = paste.value, request = ++revision, wasEdited = edited;
          if (busy || !text.trim()) return;
          notice.textContent = '正在识别…';
          try {
            const res = await fetch(base + '/parse', {method:'POST', body:text});
            if (!res.ok) throw new Error('识别请求失败，请重新打开电视上的完整链接');
            const list = await res.json(); if (request !== revision) return;
            if (!list.length) { notice.textContent = '未识别到线路地址，请手动填写，或粘贴包含地址和账号的信息。'; return; }
            if (!wasEdited) servers.replaceChildren();
            for (const server of list) {
              const existing = Array.from(servers.children).find(card => card.querySelector('.name').value.trim() === server.name && card.querySelector('.user').value.trim() === server.user && card.querySelector('.pass').value === server.pass);
              if (!existing) addServer(server);
              else for (const line of server.lines) {
                if (!Array.from(existing.querySelectorAll('.url')).some(input => input.value.trim().replace(/\/${'$'}/,'') === line.url.replace(/\/${'$'}/,''))) addLine(existing, line);
              }
            }
            edited = true;
            const count = list.reduce((n,s) => n + s.lines.length,0);
            notice.textContent = '已' + (wasEdited ? '追加' : '填写') + ' ' + list.length + ' 台服务器、' + count + ' 条线路，请检查账号和密码。';
          } catch(e) { if (request === revision) notice.textContent = e.message; }
        }
        paste.addEventListener('paste', () => setTimeout(recognize, 0));
        document.getElementById('recognize').onclick = recognize;
        form.onsubmit = async e => {
          e.preventDefault(); if (busy || !form.reportValidity()) return;
          const entries = readServers(); if (!entries.length) return;
          revision++; busy = true;
          const controls = Array.from(document.querySelectorAll('button,input,textarea,fieldset')); controls.forEach(el => el.disabled = true);
          const results = document.getElementById('results'); results.replaceChildren();
          const pending = document.createElement('li'); pending.textContent = '正在逐条线路登录…'; results.appendChild(pending);
          try {
            const res = await fetch(base, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({servers:entries})});
            if (!res.ok) throw new Error(res.status === 400 ? '请检查线路地址和账号后重试' : '添加失败，请检查电视上的扫码页是否仍然打开');
            const list = await res.json(); results.replaceChildren();
            for (const x of list) { const li = document.createElement('li'); li.className = x.ok ? 'ok' : 'no'; li.textContent = x.label + ' · ' + x.message; results.appendChild(li); }
            if (!list.length) throw new Error('没有可添加的线路');
            if (list.every(x => x.ok)) { servers.replaceChildren(); addServer(); paste.value = ''; edited = false; notice.textContent = '已添加，可继续填写下一台服务器。'; }
          } catch(e) { results.replaceChildren(); const li = document.createElement('li'); li.className = 'no'; li.textContent = e.message; results.appendChild(li); }
          finally { busy = false; controls.forEach(el => el.disabled = false); }
        };
        addServer();
        </script></main></body></html>
    """.trimIndent()
}
