<script setup>
// What Taskz can do, for its users: one section per feature, each saying where to find it.
// Open without a login, so it can be linked from anywhere. Keep it in step with the features:
// something that is only reachable through a menu is invisible unless it is listed here.
const SECTIONS = [
  {
    id: 'boards',
    title: 'Boards',
    icon: 'pi pi-th-large',
    intro: 'A board holds your tasks in columns. You can have as many boards as you like.',
    points: [
      ['Create and switch', 'The sidebar lists your boards and the ones you joined; “New board” is at its bottom. On phones the sidebar opens with the ☰ button.'],
      ['Rename and delete', 'In the board’s Settings. Renaming changes the board’s address; share links keep working.'],
      ['Hide the sidebar', 'The « button collapses it on wide screens.'],
    ],
  },
  {
    id: 'columns',
    title: 'Columns',
    icon: 'pi pi-objects-column',
    intro: 'Columns are the steps a task goes through, e.g. To do, In progress, Done.',
    points: [
      ['Add', '“Add column” next to the search; on phones the + at the end of the column tabs.'],
      ['Edit, move, delete', 'The ⋯ menu in a column’s header: name and color, move left or right, delete. Deleting a column with tasks asks whether they move to another column or are deleted too.'],
      ['Empty columns', 'On wide screens a column without tasks shrinks to a narrow strip.'],
    ],
  },
  {
    id: 'tasks',
    title: 'Tasks',
    icon: 'pi pi-check-square',
    intro: 'A task has a title and, if you want, a description, a due date, labels, a checklist, attachments and notes.',
    points: [
      ['Create', '“New task” next to the search, or “Add task” at the bottom of a column.'],
      ['Open and edit', 'Click a task to read it; “Edit” changes it. The description understands Markdown, with a short formatting help under the field.'],
      ['Move', 'Drag a task to another place or column, or use “Move to” in its ⋮ menu.'],
      ['The ⋮ menu', 'Every task has one, on its card and in the table: Edit, Move to, Archive, Delete.'],
      ['History', 'The task shows who changed what and when, including what an automation did.'],
      ['Task template', 'In the board’s Settings: a description every new task of the board starts with.'],
    ],
  },
  {
    id: 'views',
    title: 'Views',
    icon: 'pi pi-table',
    intro: 'The switch at the top right shows the same board in different ways. Your choice is remembered per board on this device.',
    points: [
      ['Board', 'Columns side by side with full cards.'],
      ['Compact', 'The same columns with one-line cards.'],
      ['Table', 'All tasks as a sortable table. “Columns” chooses which columns it shows.'],
      ['Archive', 'Only there once the board has archived tasks, see below.'],
    ],
  },
  {
    id: 'search',
    title: 'Search and filter',
    icon: 'pi pi-search',
    intro: 'The row above the board narrows down what you see. On phones it opens with the magnifier.',
    points: [
      ['Search', 'Type to show only matching tasks. The / key jumps into the field, Esc clears it.'],
      ['Labels', 'Show only tasks with certain labels, or those without any.'],
    ],
  },
  {
    id: 'archive',
    title: 'Archive',
    icon: 'pi pi-inbox',
    intro: 'Archiving takes a finished task off the board without deleting it.',
    points: [
      ['Archive a task', '“Archive” in the task’s ⋮ menu. There is no question first, as the task can come back.'],
      ['See the archive', 'As soon as a board has archived tasks, “Archive” appears in the view switch.'],
      ['Restore or delete', 'In the archive, “Restore” puts a task back at the end of its column; the trash deletes it for good.'],
      ['Automatically', 'An automation can archive tasks that stayed in a column for some days.'],
    ],
  },
  {
    id: 'labels',
    title: 'Labels',
    icon: 'pi pi-tag',
    intro: 'Colored tags to group tasks across columns.',
    points: [
      ['Create', 'While editing a task, or in the board’s Settings, where they can also be renamed, recolored and deleted.'],
      ['Use', 'Labels show on the cards and in the table, and the filter can narrow the board down to them.'],
    ],
  },
  {
    id: 'checklists',
    title: 'Checklists',
    icon: 'pi pi-list-check',
    intro: 'Each task can have a list of items to tick off.',
    points: [
      ['Edit', 'In the task’s edit form. Items can be ticked right in the task without editing it.'],
      ['Progress', 'Cards and the table show how many items are done.'],
      ['Automatically', 'An automation can add items when a task enters a column, or move the task when the last item is ticked.'],
    ],
  },
  {
    id: 'notes',
    title: 'Notes and reminders',
    icon: 'pi pi-comment',
    intro: 'Notes are comments on a task, visible to everyone on the board.',
    points: [
      ['Write', 'Open a task and add a note below it. You can edit and delete your own notes.'],
      ['Reminder', 'A note can carry a date. It then shows in your “What’s due” until you mark it done; only you see your reminders.'],
    ],
  },
  {
    id: 'attachments',
    title: 'Attachments',
    icon: 'pi pi-paperclip',
    intro: 'Files attached to a task.',
    points: [
      ['Add', 'Open a task and drop files into it, or pick them. The accepted types (images, PDF, text files) and the size limit are shown there.'],
      ['Open', 'Text and Markdown files open in a preview, everything else in a new tab.'],
    ],
  },
  {
    id: 'due',
    title: 'What’s due',
    icon: 'pi pi-calendar',
    intro: 'One list of everything with a date, across all your boards. The link is at the top of the sidebar.',
    points: [
      ['What it shows', 'Tasks with a due date and your note reminders, grouped into Overdue, Today, Next 7 days and Later. Tasks in a board’s last column count as done and are left out.'],
      ['From there', 'A click opens the task on its board; “Done” ticks off a reminder.'],
    ],
  },
  {
    id: 'together',
    title: 'Working together',
    icon: 'pi pi-users',
    intro: 'A board can have members who work on it with you.',
    points: [
      ['Invite', 'In the board’s Settings under Members: an invite link works once and for 7 days. Whoever opens it and logs in joins the board.'],
      ['What members can do', 'Everything on the board except sharing it, managing members and deleting it.'],
      ['Live', 'Changes by others appear without reloading, and the header shows who else has the board open.'],
      ['Editing at the same time', 'If someone saves a task while you are editing it, you are asked whether to save yours over theirs or to see their version.'],
    ],
  },
  {
    id: 'share',
    title: 'Share link',
    icon: 'pi pi-link',
    intro: 'A link that shows the board to anyone, without a login and without letting them change anything.',
    points: [
      ['Create', 'In the board’s Settings under Share link. The board behind it updates live, too.'],
      ['Take it back', '“Stop sharing” ends it; “Replace link” makes a new one and the old one stops working.'],
    ],
  },
  {
    id: 'automations',
    title: 'Automations',
    icon: 'pi pi-bolt',
    intro: 'Rules that run by themselves when something happens to a task in a column. The board’s owner sets them up in the Settings.',
    points: [
      ['When a task enters a column', 'Add checklist items, replace the checklist, add a note with a reminder, or notify people.'],
      ['When a task stays in a column for some days', 'Archive it, notify people, or move it to another column. This is checked every hour.'],
      ['When a task completes its checklist in a column', 'Move it to another column, or notify people.'],
    ],
  },
  {
    id: 'notifications',
    title: 'Notifications',
    icon: 'pi pi-bell',
    intro: 'The bell at the top collects messages for you.',
    points: [
      ['Where they come from', 'From automations that notify people, e.g. “Ready for review” when a task enters a column.'],
      ['Reading them', 'The number counts what you have not seen yet. A click on a message opens its task; the dot stays until you clicked it or chose “Mark all read”.'],
    ],
  },
  {
    id: 'account',
    title: 'Account',
    icon: 'pi pi-user',
    intro: 'Your icon at the top right opens a menu with your account settings and the logout.',
    points: [
      ['Logged in on', 'The devices you are logged in on; you can log out single ones or all others.'],
      ['API tokens', 'For scripts and tools: a token acts as you on one board and can do only what you tick, e.g. read tasks, create them or write notes.'],
      ['Your data', 'Download everything Taskz has of you as a JSON file, or delete your account.'],
      ['Light and dark', 'The button next to your icon switches between light, dark and your system’s setting.'],
    ],
  },
]
</script>

<template>
  <div class="mx-auto flex max-w-3xl flex-col gap-8" data-testid="help">
    <header class="flex flex-col gap-2">
      <h1 class="text-2xl font-semibold tracking-tight text-slate-900 dark:text-slate-100">What Taskz can do</h1>
      <p class="text-slate-600 dark:text-slate-400">
        Everything Taskz offers and where to find it. Some of it only shows in a menu, so it is easy to miss.
      </p>
    </header>

    <nav aria-label="Topics" class="flex flex-wrap gap-2">
      <a
        v-for="section in SECTIONS"
        :key="section.id"
        :href="`#${section.id}`"
        class="rounded-full border border-slate-200 bg-white px-3 py-1 text-sm text-slate-700 hover:border-emerald-500 hover:text-emerald-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-emerald-400 dark:hover:text-emerald-300"
      >{{ section.title }}</a>
    </nav>

    <!-- scroll-mt: the sticky app header would cover the heading a topic link jumps to. -->
    <section
      v-for="section in SECTIONS"
      :id="section.id"
      :key="section.id"
      :aria-labelledby="`${section.id}-title`"
      class="flex scroll-mt-20 flex-col gap-3 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900"
    >
      <h2 :id="`${section.id}-title`" class="flex items-center gap-2 text-lg font-semibold text-slate-900 dark:text-slate-100">
        <i :class="section.icon" class="text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
        {{ section.title }}
      </h2>
      <p class="text-slate-600 dark:text-slate-400">{{ section.intro }}</p>
      <dl class="flex flex-col gap-2">
        <div v-for="[term, text] in section.points" :key="term" class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-3">
          <dt class="font-medium text-slate-900 dark:text-slate-100">{{ term }}</dt>
          <dd class="text-slate-600 dark:text-slate-400">{{ text }}</dd>
        </div>
      </dl>
    </section>

    <RouterLink to="/boards" class="self-start text-sm font-medium text-emerald-700 hover:underline dark:text-emerald-400">
      ← To your boards
    </RouterLink>
  </div>
</template>
