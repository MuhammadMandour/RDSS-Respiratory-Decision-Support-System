import { http as mswHttp, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { api, ApiRequestError, configureAuth } from './client';

describe('api client', () => {
  afterEach(() => configureAuth(() => null, () => {}));

  it('attaches the bearer token when one is available', async () => {
    let seen: string | null = null;
    server.use(
      mswHttp.get('*/api/v1/echo', ({ request }) => {
        seen = request.headers.get('authorization');
        return HttpResponse.json({ ok: true });
      }),
    );
    configureAuth(() => 'abc.def.ghi', () => {});

    await api.get('/echo');

    expect(seen).toBe('Bearer abc.def.ghi');
  });

  it('normalizes validation errors into ApiRequestError', async () => {
    server.use(
      mswHttp.post('*/api/v1/things', () =>
        HttpResponse.json(
          { status: 400, error: 'Bad Request', message: 'Validation failed', path: '/x', fieldErrors: [{ field: 'name', message: 'must not be blank' }] },
          { status: 400 },
        ),
      ),
    );

    const err = await api.post('/things', {}).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiRequestError);
    expect((err as ApiRequestError).status).toBe(400);
    expect((err as ApiRequestError).fieldErrors).toEqual([{ field: 'name', message: 'must not be blank' }]);
  });

  it('invokes the unauthorized handler on 401 when a session exists', async () => {
    const onUnauthorized = vi.fn();
    configureAuth(() => 'expired', onUnauthorized);
    server.use(mswHttp.get('*/api/v1/secure', () => HttpResponse.json({ message: 'Authentication required' }, { status: 401 })));

    await expect(api.get('/secure')).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});
