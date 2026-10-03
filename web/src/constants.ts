export interface HomeModeOption {
  value: string;
  title: string;
  desc: string;
}

export const HOME_MODES: HomeModeOption[] = [
  { value: "ISOLATED", title: "独立实例（推荐）", desc: "每个实例拥有独立的数据目录，互不影响" },
  { value: "VERSION_SHARED", title: "版本共享", desc: "相同版本的实例共享同一个数据目录" },
  { value: "GLOBAL", title: "全局共享", desc: "所有实例共享一个全局数据目录" },
  { value: "CUSTOM", title: "自定义", desc: "使用自定义路径作为实例数据目录" },
];

export const MAX_LOG_LINES = 500;
export const LOG_TAIL = 200;
