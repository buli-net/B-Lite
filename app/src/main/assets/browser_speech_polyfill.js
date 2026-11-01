(function () {
    'use strict';

    if (location.protocol !== 'https:') return;
    try {
        if (window.top.location.origin !== window.location.origin) return;
    } catch (ignored) {
        return;
    }
    if (window.__nativeSpeechRecognitionPolyfillInstalled) return;

    var bridge = window.NativeSpeechBridge;
    if (!bridge || typeof bridge.postMessage !== 'function'
            || typeof bridge.addEventListener !== 'function') return;

    // Android WebView may expose the Web Speech API surface without a working
    // recognition backend. Always use this app's Android SpeechRecognizer bridge.

    var sessions = Object.create(null);
    var nextSessionId = 1;

    function makeList(items) {
        var result = items.slice();
        result.item = function (index) {
            return result[index] || null;
        };
        return result;
    }

    function makeAlternatives(values) {
        var alternatives = (values || []).map(function (value) {
            return {
                transcript: String(value.transcript || ''),
                confidence: Number(value.confidence) || 0
            };
        });
        return makeList(alternatives);
    }

    function makeResult(values, isFinal) {
        var result = makeAlternatives(values);
        result.isFinal = !!isFinal;
        return result;
    }

    function makeEvent(type, detail) {
        var event = new Event(type);
        if (detail) {
            Object.keys(detail).forEach(function (key) {
                try {
                    Object.defineProperty(event, key, {
                        configurable: true,
                        enumerable: true,
                        value: detail[key]
                    });
                } catch (ignored) { }
            });
        }
        return event;
    }

    function dispatch(recognition, type, detail) {
        var event = makeEvent(type, detail);
        // EventTarget dispatch invokes both addEventListener() and on<type>
        // handlers. Calling the property handler manually here would fire it twice.
        try { recognition.dispatchEvent(event); } catch (ignored) { }
    }

    class NativeSpeechRecognition extends EventTarget {
        constructor() {
            super();
            this.lang = navigator.language || 'en-US';
            this.continuous = false;
            this.interimResults = false;
            this.maxAlternatives = 1;
            this.grammars = null;

            this.onstart = null;
            this.onaudiostart = null;
            this.onsoundstart = null;
            this.onspeechstart = null;
            this.onspeechend = null;
            this.onsoundend = null;
            this.onaudioend = null;
            this.onresult = null;
            this.onnomatch = null;
            this.onerror = null;
            this.onend = null;

            this._id = null;
            this._active = false;
            this._stopRequested = false;
            this._finalResults = [];
            this._interimResult = null;
        }

        start() {
            if (this._active) {
                throw new DOMException('Recognition has already started', 'InvalidStateError');
            }
            if (location.protocol !== 'https:') {
                dispatch(this, 'error', {
                    error: 'not-allowed',
                    message: 'A secure origin is required'
                });
                dispatch(this, 'end');
                return;
            }
            if (navigator.userActivation && !navigator.userActivation.isActive) {
                dispatch(this, 'error', {
                    error: 'not-allowed',
                    message: 'A user gesture is required'
                });
                dispatch(this, 'end');
                return;
            }

            var id = String(Date.now()) + '-' + String(nextSessionId++);
            this._id = id;
            this._active = true;
            this._stopRequested = false;
            this._finalResults = [];
            this._interimResult = null;
            sessions[id] = this;

            try {
                bridge.postMessage(JSON.stringify({
                    type: 'start',
                    id: id,
                    lang: this.lang || navigator.language || 'en-US',
                    continuous: !!this.continuous,
                    interimResults: !!this.interimResults,
                    maxAlternatives: Math.max(1, Math.min(5, Number(this.maxAlternatives) || 1))
                }));
            } catch (error) {
                delete sessions[id];
                this._active = false;
                this._id = null;
                dispatch(this, 'error', {
                    error: 'service-not-allowed',
                    message: String(error)
                });
                dispatch(this, 'end');
            }
        }

        stop() {
            if (!this._active || !this._id) return;
            this._stopRequested = true;
            try {
                bridge.postMessage(JSON.stringify({ type: 'stop', id: this._id }));
            } catch (ignored) { }
        }

        abort() {
            if (!this._active || !this._id) return;
            this._stopRequested = true;
            try {
                bridge.postMessage(JSON.stringify({ type: 'abort', id: this._id }));
            } catch (ignored) { }
        }
    }

    function onNativeMessage(message) {
        var data;
        try {
            data = typeof message.data === 'string' ? JSON.parse(message.data) : null;
        } catch (ignored) {
            return;
        }
        if (!data || !data.id || !data.event) return;

        var recognition = sessions[data.id];
        if (!recognition) return;

        var detail = data.detail || {};
        if (data.event === 'result') {
            var values = makeAlternatives(detail.alternatives || []);
            var result = makeResult(values, !!detail.isFinal);
            if (result.length === 0) return;

            if (result.isFinal) {
                recognition._finalResults.push(result);
                recognition._interimResult = null;
            } else {
                recognition._interimResult = result;
            }

            var resultItems = recognition._finalResults.slice();
            if (recognition._interimResult) resultItems.push(recognition._interimResult);
            var resultList = makeList(resultItems);
            var resultIndex = result.isFinal
                ? Math.max(0, recognition._finalResults.length - 1)
                : recognition._finalResults.length;

            dispatch(recognition, 'result', {
                resultIndex: resultIndex,
                results: resultList
            });
            return;
        }

        if (data.event === 'error') {
            dispatch(recognition, 'error', {
                error: String(detail.error || 'unknown'),
                message: String(detail.message || '')
            });
            return;
        }

        if (data.event === 'speechstart') {
            dispatch(recognition, 'soundstart');
            dispatch(recognition, 'speechstart', detail);
            return;
        }
        if (data.event === 'speechend') {
            dispatch(recognition, 'speechend', detail);
            dispatch(recognition, 'soundend');
            return;
        }
        if (data.event === 'end') {
            recognition._active = false;
            recognition._id = null;
            recognition._interimResult = null;
            delete sessions[data.id];
            dispatch(recognition, 'end', detail);
            return;
        }
        dispatch(recognition, data.event, detail);
    }

    bridge.addEventListener('message', onNativeMessage);

    var standardInstalled = false;
    var webkitInstalled = false;
    try {
        Object.defineProperty(window, 'SpeechRecognition', {
            configurable: true,
            writable: true,
            value: NativeSpeechRecognition
        });
        standardInstalled = window.SpeechRecognition === NativeSpeechRecognition;
    } catch (ignored) {
        try {
            window.SpeechRecognition = NativeSpeechRecognition;
            standardInstalled = window.SpeechRecognition === NativeSpeechRecognition;
        } catch (ignoredAgain) { }
    }
    try {
        Object.defineProperty(window, 'webkitSpeechRecognition', {
            configurable: true,
            writable: true,
            value: NativeSpeechRecognition
        });
        webkitInstalled = window.webkitSpeechRecognition === NativeSpeechRecognition;
    } catch (ignored) {
        try {
            window.webkitSpeechRecognition = NativeSpeechRecognition;
            webkitInstalled = window.webkitSpeechRecognition === NativeSpeechRecognition;
        } catch (ignoredAgain) { }
    }
    if (standardInstalled && webkitInstalled) {
        window.__nativeSpeechRecognitionPolyfillInstalled = true;
    }
})();
