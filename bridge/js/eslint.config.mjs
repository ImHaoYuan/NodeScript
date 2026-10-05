// B8：facade 的 ESLint（flat config）。口径：**首日即绿** —— 只启用当前源码已满足的规则，
// 等价于「此后新增违规即红」；不做全量重排/格式化（存量风格由 tsc strict + 既有测试守着）。
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    // 只扫源码；dist 是 tsc 产物、test 是 node --test 的 CJS 用例（各自另有门）。
    files: ['src/**/*.ts'],
    extends: [...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
    },
    rules: {
      // A7 同类：吞异常在本层同样不该新增（no-empty 的 allowEmptyCatch 刻意不开）。
      'no-empty': ['error', { allowEmptyCatch: false }],
      // 未用变量/参数：下划线前缀显式表达「有意不用」。
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // 显式 any 在 facdae 边界上是漏网口，禁掉（unknown 才是正确形态）。
      '@typescript-eslint/no-explicit-any': 'error',
      eqeqeq: ['error', 'smart'],
      // prefer-const 关掉：本仓有「闭包先引用、随后才赋值」的合法形态
      // （sensors.ts 的 interval 句柄：tick 闭包里读它，赋值在 setInterval 之后）。
      'prefer-const': 'off',
      // prefer-const 刻意不开：本仓有「闭包先引用、随后才赋值」的合法形态
      // （sensors.ts 的 interval 句柄：tick 闭包里读它，赋值在 setInterval 之后）。
      'no-var': 'error',
      'no-throw-literal': 'error',
    },
  },
  {
    // 生成物（wire-types.ts）是构建产物，另有 `npm run gen:wire && git diff --exit-code`
    // 同步门守着 —— 不按手写规则判（它自带的 eslint-disable 会报 unused directive）。
    ignores: ['src/generated/**'],
  },
);
