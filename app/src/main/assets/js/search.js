let loadingCount = 0;
let warnToastTimer = null;
let pollTimer = null;
let currentWord = '';

function escHtml(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
function showLoading() { loadingCount++; $('#loadingToast').show(); }
function hideLoading() { loadingCount = Math.max(0, loadingCount - 1); if (loadingCount === 0) $('#loadingToast').hide(); }
function warnToast(msg) { $('#warnToastContent').text(msg); $('#warnToast').show(); if (warnToastTimer) clearTimeout(warnToastTimer); warnToastTimer = setTimeout(() => { $('#warnToast').hide(); warnToastTimer = null; }, 1500); }
function openDialog(id) { $('#' + id).show(); history.pushState({ dialog: id }, ''); }
function closeDialog(id) { $('#' + id).hide(); history.back(); }
function closeDetailDialog() { closeDialog('detailDialog'); }

window.addEventListener('popstate', () => { $('.md-dialog-overlay').hide(); });

function startSearch() {
    const word = $('#searchWord').val().trim();
    if (!word) { warnToast('請輸入搜尋關鍵字'); return; }
    currentWord = word;
    stopPoll();
    $('#resultList').empty();
    $('#searchStatus').text('搜尋中...');
    showLoading();
    $.ajax({ url: '/s/query', type: 'post', data: { word }, timeout: 10000 })
        .done(() => { pollResult(); })
        .fail((xhr, status) => { warnToast(requestError(xhr, status, '搜尋發送失敗')); $('#searchStatus').text(''); })
        .always(hideLoading);
}

function pollResult() {
    pollTimer = setInterval(() => {
        $.ajax({ url: '/s/result', type: 'get', data: { word: currentWord }, timeout: 8000 })
            .done(data => renderResult(parseJson(data)))
            .fail(() => { /* keep polling on transient errors */ });
    }, 2000);
}

function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

function parseJson(data) {
    return typeof data === 'string' ? JSON.parse(data) : data;
}

function requestError(xhr, status, fallback) {
    if (xhr && xhr.responseText) { try { return parseJson(xhr.responseText).error || fallback; } catch (e) { return fallback; } }
    return fallback;
}

function renderResult(data) {
    const total = data.total || 0;
    const completed = data.completed || 0;
    $('#searchStatus').text(data.done ? `搜尋完成，共 ${data.items.length} 筆結果` : `搜尋中 (${completed}/${total})...`);
    const html = (data.items || []).map((item, index) => `
        <div class="search-result-card" onclick="openDetail(${index})">
            <img class="search-result-pic" src="${escHtml(item.pic)}" loading="lazy" onerror="this.style.visibility='hidden'" />
            <div class="search-result-info">
                <div class="search-result-name">${escHtml(item.name)}</div>
                <div class="search-result-meta">${escHtml(item.siteName)} · ${escHtml(item.remarks)} ${escHtml(item.year)} ${escHtml(item.area)}</div>
            </div>
        </div>`).join('');
    $('#resultList').html(html);
    window.__searchItems = data.items || [];
    if (data.done) stopPoll();
}

function openDetail(index) {
    const item = (window.__searchItems || [])[index];
    if (!item) return;
    showLoading();
    $.ajax({ url: '/s/detail', type: 'get', data: { site: item.siteKey, id: item.vodId }, timeout: 10000 })
        .done(data => renderDetail(item, parseJson(data)))
        .fail((xhr, status) => warnToast(requestError(xhr, status, '取得詳情失敗')))
        .always(hideLoading);
}

function renderDetail(item, data) {
    $('#detailTitle').text(data.name || item.name);
    const flags = data.flags || [];
    if (!flags.length) { $('#detailFlags').html('<div class="manage-subtitle">未取得可播放線路</div>'); openDialog('detailDialog'); return; }
    const html = flags.map(flag => `
        <div class="search-flag-group">
            <div class="search-flag-show">${escHtml(flag.show)}</div>
            <div class="search-episode-grid">
                ${(flag.episodes || []).map(ep => `<button class="search-episode-btn" onclick="playEpisode('${escJs(item.siteKey)}','${escJs(item.vodId)}','${escJs(ep.name)}')" type="button">${escHtml(ep.name)}</button>`).join('')}
            </div>
        </div>`).join('');
    $('#detailFlags').html(html);
    openDialog('detailDialog');
}

function escJs(s) { return String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "\\'"); }

function playEpisode(siteKey, vodId, mark) {
    showLoading();
    $.ajax({ url: '/s/play', type: 'post', data: { site: siteKey, id: vodId, mark }, timeout: 10000 })
        .done(() => { warnToast('已送出播放'); closeDetailDialog(); })
        .fail((xhr, status) => warnToast(requestError(xhr, status, '播放發送失敗')))
        .always(hideLoading);
}
