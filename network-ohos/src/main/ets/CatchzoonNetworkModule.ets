import { rcp } from '@kit.RemoteCommunicationKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';

interface NetworkRequestPayload {
  url: string;
  method: string;
  param?: Record<string, Object>;
  headers?: Record<string, string>;
  cookie?: string;
  timeout?: number;
  maxResponseBytes?: number;
}

/** Kuikly 的鸿蒙网络桥接；序列化、重试和业务错误仍由共享网络层负责。 */
export class CatchzoonNetworkModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'KRNetworkModule';
  private readonly sessions: Set<rcp.Session> = new Set();

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (method === 'cancelAll') {
      this.cancelAll();
      return null;
    }
    if (method !== 'httpRequest') {
      callback?.(this.failure('unsupported_method', '鸿蒙网络桥接不支持该方法'));
      return null;
    }
    const payload: NetworkRequestPayload | null = this.parsePayload(params);
    if (payload === null || !this.isSafeRequest(payload)) {
      callback?.(this.failure('invalid_request', '网络请求参数无效'));
      return null;
    }
    this.execute(payload, callback);
    return null;
  }

  private async execute(
    payload: NetworkRequestPayload,
    callback: KuiklyRenderCallback | null
  ): Promise<void> {
    let session: rcp.Session | undefined;
    try {
      session = rcp.createSession();
      this.sessions.add(session);
      const method: rcp.HttpMethod = payload.method;
      const headers: rcp.RequestHeaders = this.safeHeaders(payload.headers ?? {});
      if ((payload.cookie ?? '').length > 0 && headers['Cookie'] === undefined) {
        headers['Cookie'] = payload.cookie ?? '';
      }
      const body: string = JSON.stringify(payload.param ?? {});
      const timeout: number = Math.min(Math.max((payload.timeout ?? 30) * 1000, 1000), 300000);
      const request: rcp.Request = new rcp.Request(
        payload.url,
        method,
        headers,
        method === 'GET' || method === 'HEAD' ? undefined : body,
        undefined,
        undefined,
        {
          transfer: {
            timeout: {
              connectMs: timeout,
              transferMs: timeout,
              inactivityMs: timeout
            }
          }
        }
      );
      request.destination = 'array-buffer';
      const response: rcp.Response = await session.fetch(request);
      if ((response.body?.byteLength ?? 0) > (payload.maxResponseBytes ?? 2 * 1024 * 1024)) {
        callback?.(this.failure('response_too_large', '响应体超过大小限制'));
        return;
      }
      const responseBody: string = response.toString() ?? '';
      callback?.({
        'data': responseBody,
        'headers': JSON.stringify(response.headers ?? {}),
        'success': response.statusCode >= 200 && response.statusCode < 300 ? 1 : 0,
        'errorMsg': response.statusCode >= 200 && response.statusCode < 300 ? '' : `HTTP ${response.statusCode}`,
        'statusCode': response.statusCode
      });
    } catch (error) {
      callback?.(this.failure('network_unavailable', `${error}`));
    } finally {
      if (session !== undefined) {
        this.sessions.delete(session);
        session.close();
      }
    }
  }

  private cancelAll(): void {
    this.sessions.forEach((session: rcp.Session) => session.close());
    this.sessions.clear();
  }

  private parsePayload(params: KRAny): NetworkRequestPayload | null {
    if (typeof params !== 'string') return null;
    try {
      return JSON.parse(params) as NetworkRequestPayload;
    } catch (error) {
      return null;
    }
  }

  private isSafeRequest(payload: NetworkRequestPayload): boolean {
    if (!payload.url.startsWith('https://') || payload.url.indexOf('@') >= 0 || payload.url.indexOf('#') >= 0) {
      return false;
    }
    if (payload.timeout !== undefined && (payload.timeout < 1 || payload.timeout > 300)) return false;
    if (payload.maxResponseBytes !== undefined &&
      (payload.maxResponseBytes < 1 || payload.maxResponseBytes > 20 * 1024 * 1024)) return false;
    return ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].indexOf(payload.method) >= 0;
  }

  private safeHeaders(values: Record<string, string>): Record<string, string> {
    const result: Record<string, string> = {};
    Object.keys(values).forEach((name: string) => {
      const value: string = values[name];
      if (name.length > 0 && name.indexOf('\n') < 0 && name.indexOf('\r') < 0 &&
        value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
        result[name] = value;
      }
    });
    return result;
  }

  private failure(code: string, message: string): Record<string, Object> {
    return {
      'data': '',
      'headers': '{}',
      'success': 0,
      'errorMsg': `${code}: ${message}`,
      'statusCode': 0
    };
  }
}
