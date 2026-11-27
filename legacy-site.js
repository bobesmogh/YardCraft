(() => {
  const destination = new URL('https://yardcraft.github.io/');
  destination.search = location.search;
  destination.hash = location.hash;

  if (location.pathname.endsWith('/Instruction.html')) {
    destination.pathname = '/Instruction.html';
  } else if (['#install', '#tab-install', '#panel-install'].includes(location.hash)) {
    destination.pathname = '/builds/';
    destination.hash = '#install';
  } else if (['#events', '#tab-events', '#panel-events'].includes(location.hash)) {
    destination.pathname = '/journal/events/';
    destination.hash = '';
  } else if (/^#step-[1-8]$/.test(location.hash)) {
    destination.pathname = '/builds/';
  }

  location.replace(destination.href);
})();
