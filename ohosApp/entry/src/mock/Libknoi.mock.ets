export const setup = (_soName: string, _debug: boolean): void => {};
export const init = (): void => {};
export const create_function_waiter = (): number => 0;
export const notify_function_waiter = (_id: number, _result: string, _length: number): void => {};
export const wait_on_function_waiter = (_id: number): string => '';

const NativeMock: Record<string, Object> = {
  'setup': setup,
  'init': init,
  'create_function_waiter': create_function_waiter,
  'notify_function_waiter': notify_function_waiter,
  'wait_on_function_waiter': wait_on_function_waiter,
};

export default NativeMock;
