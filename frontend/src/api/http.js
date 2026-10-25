const STORAGE_KEY = 'msd.auth'
const AUTH_PREFIX = '/api/user/auth/'

/**
 * 后端统一响应或网络错误。
 *
 * status 是 HTTP 状态码，code 是后端业务码，便于页面区分 401/403/404/409。
 */
export class ApiError extends Error {
    constructor(code, message, status = 0, payload = null) {
        super(message)
        this.name = 'ApiError'
        this.code = code
        this.status = status
        this.payload = payload
    }
}

/**
 * 从 localStorage 读取当前 Token，避免和 Pinia 形成循环依赖。
 */
function currentToken() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY)
        return raw ? JSON.parse(raw).token || '' : ''
    } catch {
        return ''
    }
}

/**
 * 拼接查询参数，跳过空值但保留数字 0。
 */
function withQuery(path, query) {
    if (!query) {
        return path
    }
    const params = new URLSearchParams()
    Object.entries(query).forEach(([key, value]) => {
        if (value === undefined || value === null || value === '') {
            return
        }
        params.append(key, String(value))
    })
    const search = params.toString()
    if (!search) {
        return path
    }
    return `${path}${path.includes('?') ? '&' : '?'}${search}`
}

/**
 * 统一请求封装：解析 Result、附加 Token、处理 401。
 */
export async function request(path, options = {}) {
    const {
        method = 'GET',
        body,
        query,
        headers: extraHeaders,
        auth = true,
        authRedirect = true,
        keepalive = false
    } = options

    // 1. 组装请求头
    const headers = { Accept: 'application/json', ...extraHeaders }
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json'
    }
    const token = currentToken()
    if (auth && token) {
        headers.Authorization = `Bearer ${token}`
    }

    // 2. 发起请求，网络异常统一转成业务可展示的错误
    const target = withQuery(path, query)
    let response
    try {
        response = await fetch(target, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            keepalive
        })
    } catch {
        throw new ApiError(-1, '网络异常，请确认网关和服务已启动')
    }

    // 3. 尽量解析统一响应体
    let payload = null
    try {
        payload = await response.json()
    } catch {
        payload = null
    }

    // 4. 非认证接口的 401 触发会话清理
    if (response.status === 401 && authRedirect && !path.startsWith(AUTH_PREFIX)) {
        window.dispatchEvent(new CustomEvent('msd:unauthorized'))
    }

    // 5. HTTP 错误和业务错误都抛 ApiError
    if (!response.ok) {
        throw new ApiError(
            payload?.code ?? response.status,
            payload?.message || `请求失败（HTTP ${response.status}）`,
            response.status,
            payload
        )
    }
    if (!payload || payload.success !== true) {
        throw new ApiError(
            payload?.code ?? -1,
            payload?.message || '请求失败',
            response.status,
            payload
        )
    }

    return payload.data
}
